package com.sequenceiq.cloudbreak.cloud.openstack.heat;

import static com.google.common.collect.Lists.newArrayList;
import static com.sequenceiq.cloudbreak.cloud.openstack.common.OpenStackConstants.NETWORK_ID;
import static com.sequenceiq.cloudbreak.cloud.openstack.common.OpenStackConstants.SUBNET_ID;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient;
import org.openstack4j.model.compute.Keypair;
import org.openstack4j.model.heat.Stack;
import org.openstack4j.model.heat.StackUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.sequenceiq.cloudbreak.api.model.AdjustmentType;
import com.sequenceiq.cloudbreak.cloud.ResourceConnector;
import com.sequenceiq.cloudbreak.cloud.context.AuthenticatedContext;
import com.sequenceiq.cloudbreak.cloud.exception.CloudConnectorException;
import com.sequenceiq.cloudbreak.cloud.exception.TemplatingDoesNotSupportedException;
import com.sequenceiq.cloudbreak.cloud.model.CloudInstance;
import com.sequenceiq.cloudbreak.cloud.model.CloudResource;
import com.sequenceiq.cloudbreak.cloud.model.CloudResource.Builder;
import com.sequenceiq.cloudbreak.cloud.model.CloudResourceStatus;
import com.sequenceiq.cloudbreak.cloud.model.CloudStack;
import com.sequenceiq.cloudbreak.cloud.model.Group;
import com.sequenceiq.cloudbreak.cloud.model.InstanceStatus;
import com.sequenceiq.cloudbreak.cloud.model.ResourceStatus;
import com.sequenceiq.cloudbreak.cloud.model.TlsInfo;
import com.sequenceiq.cloudbreak.cloud.notification.PersistenceNotifier;
import com.sequenceiq.cloudbreak.cloud.openstack.auth.OpenStackClient;
import com.sequenceiq.cloudbreak.cloud.openstack.common.OpenStackUtils;
import com.sequenceiq.cloudbreak.cloud.openstack.heat.HeatTemplateBuilder.ModelContext;
import com.sequenceiq.cloudbreak.cloud.openstack.view.KeystoneCredentialView;
import com.sequenceiq.cloudbreak.cloud.openstack.view.NeutronNetworkView;
import com.sequenceiq.cloudbreak.cloud.scheduler.SyncPollingScheduler;
import com.sequenceiq.cloudbreak.cloud.task.PollTask;
import com.sequenceiq.cloudbreak.cloud.task.PollTaskFactory;
import com.sequenceiq.cloudbreak.cloud.task.ResourcesStatePollerResult;
import com.sequenceiq.cloudbreak.common.type.ResourceType;
import com.sequenceiq.cloudbreak.service.Retry;
import com.sequenceiq.cloudbreak.service.Retry.ActionWentFail;

@Service
public class OpenStackResourceConnector implements ResourceConnector<Object> {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenStackResourceConnector.class);

    private static final long OPERATION_TIMEOUT = 60L;

    @Inject
    private OpenStackClient openStackClient;

    @Inject
    private HeatTemplateBuilder heatTemplateBuilder;

    @Inject
    private OpenStackUtils utils;

    @Inject
    private SyncPollingScheduler<ResourcesStatePollerResult> syncPollingScheduler;

    @Inject
    @Qualifier("DefaultRetryService")
    private Retry retryService;

    @Inject
    private PollTaskFactory pollTaskFactory;

    @SuppressWarnings("unchecked")
    @Override
    public List<CloudResourceStatus> launch(AuthenticatedContext authenticatedContext, CloudStack stack, PersistenceNotifier notifier,
            AdjustmentType adjustmentType, Long threshold) {
        String stackName = utils.getStackName(authenticatedContext);
        NeutronNetworkView neutronNetworkView = new NeutronNetworkView(stack.getNetwork());
        boolean existingNetwork = neutronNetworkView.isExistingNetwork();
        String existingSubnetCidr = getExistingSubnetCidr(authenticatedContext, stack);

        ModelContext modelContext = new ModelContext();
        modelContext.withExistingNetwork(existingNetwork);
        modelContext.withExistingSubnet(existingSubnetCidr != null);
        modelContext.withGroups(stack.getGroups());
        modelContext.withInstanceUserData(stack.getImage());
        modelContext.withLocation(authenticatedContext.getCloudContext().getLocation());
        modelContext.withStackName(stackName);
        modelContext.withNeutronNetworkView(neutronNetworkView);
        modelContext.withTemplateString(stack.getTemplate());
        modelContext.withTags(stack.getTags());

        String heatTemplate = heatTemplateBuilder.build(modelContext);
        Map<String, String> parameters = heatTemplateBuilder.buildParameters(
                authenticatedContext, stack, existingNetwork, existingSubnetCidr);

        OSClient client = openStackClient.createOSClient(authenticatedContext);

        List<CloudResourceStatus> resources;
        Stack existingStack = client.heat().stacks().getStackByName(stackName);

        if (existingStack == null) {
            if (stack.getInstanceAuthentication().getPublicKeyId() == null) {
                createKeyPair(authenticatedContext, stack, client);
            }

            Stack heatStack = client
                    .heat()
                    .stacks()
                    .create(Builders.stack().name(stackName).template(heatTemplate).disableRollback(false)
                            .parameters(parameters).timeoutMins(OPERATION_TIMEOUT).build());

            CloudResource cloudResource = new Builder().type(ResourceType.HEAT_STACK).name(heatStack.getId()).build();
            try {
                notifier.notifyAllocation(cloudResource, authenticatedContext.getCloudContext());
            } catch (RuntimeException ignored) {
                //Rollback
                terminate(authenticatedContext, stack, Collections.singletonList(cloudResource));
            }
            resources = check(authenticatedContext, Collections.singletonList(cloudResource));

            collectResources(authenticatedContext, notifier);
        } else {
            LOGGER.info("Heat stack already exists: {}", existingStack.getName());
            CloudResource cloudResource = new Builder().type(ResourceType.HEAT_STACK).name(existingStack.getId()).build();
            resources = Collections.singletonList(new CloudResourceStatus(cloudResource, ResourceStatus.CREATED));
        }
        LOGGER.debug("Launched resources: {}", resources);
        return resources;
    }

    private void collectResources(AuthenticatedContext authenticatedContext, PersistenceNotifier notifier) {
        PollTask<ResourcesStatePollerResult> task = createTask(authenticatedContext);

        try {
            ResourcesStatePollerResult call = task.call();
            if (!task.completed(call)) {
                call = syncPollingScheduler.schedule(task);
            }
            List<CloudResourceStatus> results = call.getResults();
            results.forEach(r -> notifier.notifyAllocation(r.getCloudResource(), authenticatedContext.getCloudContext()));
        } catch (Exception e) {
            LOGGER.error("Error in OS resource polling.", e);
        }
    }

    private PollTask<ResourcesStatePollerResult> createTask(AuthenticatedContext authenticatedContext) {
        return pollTaskFactory.newPollResourcesStateTask(authenticatedContext,
                Arrays.asList(
                        CloudResource.builder().type(ResourceType.OPENSTACK_NETWORK).name(NETWORK_ID).build(),
                        CloudResource.builder().type(ResourceType.OPENSTACK_SUBNET).name(SUBNET_ID).build()),
                true);
    }

    private void createKeyPair(AuthenticatedContext authenticatedContext, CloudStack stack, OSClient client) {
        KeystoneCredentialView keystoneCredential = openStackClient.createKeystoneCredential(authenticatedContext);

        String keyPairName = keystoneCredential.getKeyPairName();
        if (client.compute().keypairs().get(keyPairName) == null) {
            try {
                Keypair keyPair = client.compute().keypairs().create(keyPairName, stack.getInstanceAuthentication().getPublicKey());
                LOGGER.info("Keypair has been created: {}", keyPair);
            } catch (Exception e) {
                LOGGER.error("Failed to create keypair", e);
                throw new CloudConnectorException(e.getMessage(), e);
            }
        } else {
            LOGGER.info("Keypair already exists: {}", keyPairName);
        }
    }

    @Override
    public List<CloudResourceStatus> check(AuthenticatedContext authenticatedContext, List<CloudResource> resources) {
        List<CloudResourceStatus> result = new ArrayList<>(resources.size());
        OSClient client = openStackClient.createOSClient(authenticatedContext);

        String stackName = utils.getStackName(authenticatedContext);
        List<CloudResource> list = newArrayList();
        for (CloudResource resource : resources) {
            checkByResourceType(authenticatedContext, result, client, stackName, list, resource);
        }
        return result;
    }

    private void checkByResourceType(AuthenticatedContext authenticatedContext, List<CloudResourceStatus> result, OSClient client,
            String stackName, List<CloudResource> list, CloudResource resource) {
        switch (resource.getType()) {
            case HEAT_STACK:
                String heatStackId = resource.getName();
                LOGGER.info("Checking OpenStack Heat stack status of: {}", stackName);
                Stack heatStack = client.heat().stacks().getDetails(stackName, heatStackId);
                CloudResourceStatus heatResourceStatus = utils.heatStatus(resource, heatStack);
                result.add(heatResourceStatus);
                break;
            case OPENSTACK_NETWORK:
                collectResourcesIfNeed(authenticatedContext, stackName, list);
                result.add(getCloudResourceStatus(list, ResourceType.OPENSTACK_NETWORK));
                break;
            case OPENSTACK_SUBNET:
                collectResourcesIfNeed(authenticatedContext, stackName, list);
                result.add(getCloudResourceStatus(list, ResourceType.OPENSTACK_SUBNET));
                break;
            case OPENSTACK_ROUTER:
            case OPENSTACK_INSTANCE:
            case OPENSTACK_PORT:
            case OPENSTACK_ATTACHED_DISK:
            case OPENSTACK_SECURITY_GROUP:
            case OPENSTACK_FLOATING_IP:
                break;
            default:
                throw new CloudConnectorException(String.format("Invalid resource type: %s", resource.getType()));
        }
    }

    private void collectResourcesIfNeed(AuthenticatedContext authenticatedContext, String stackName, List<CloudResource> list) {
        if (list.isEmpty()) {
            openStackClient.getResources(stackName, authenticatedContext.getCloudCredential());
        }
    }

    private CloudResourceStatus getCloudResourceStatus(List<CloudResource> resources, ResourceType resourceType) {
        return resources.stream()
                .filter(r -> r.getType() == resourceType)
                .findFirst()
                .map(cloudResource -> new CloudResourceStatus(cloudResource, ResourceStatus.UPDATED))
                .orElseGet(() -> new CloudResourceStatus(null, ResourceStatus.IN_PROGRESS));
    }

    @Override
    public List<CloudResourceStatus> terminate(AuthenticatedContext authenticatedContext, CloudStack cloudStack, List<CloudResource> resources) {
        List<CloudResource> resourceForTermination = resources.stream()
                .filter(r -> r.getType() == ResourceType.HEAT_STACK)
                .collect(Collectors.toList());

        resourceForTermination.forEach(r -> terminateHeatStack(authenticatedContext, cloudStack, r));
        if (resourceForTermination.isEmpty()) {
            throw new CloudConnectorException("HEAT_STACK resource type is needed for stack termination!");
        }
        return check(authenticatedContext, resourceForTermination);
    }

    private void terminateHeatStack(AuthenticatedContext authenticatedContext, CloudStack cloudStack, CloudResource resource) {
        String heatStackId = resource.getName();
        String stackName = utils.getStackName(authenticatedContext);
        LOGGER.info("Terminate stack: {}", stackName);
        OSClient client = openStackClient.createOSClient(authenticatedContext);
        try {
            retryService.testWith2SecDelayMax5Times(() -> {
                boolean exists = client.heat().stacks().getStackByName(resource.getName()) != null;
                if (!exists) {
                    throw new ActionWentFail("Stack not exists");
                }
                return exists;
            });
            client.heat().stacks().delete(stackName, heatStackId);
            LOGGER.info("Heat stack has been deleted");
            if (cloudStack.getInstanceAuthentication().getPublicKeyId() == null) {
                deleteKeyPair(authenticatedContext, client);
            }
        } catch (ActionWentFail ignored) {
            LOGGER.info(String.format("Stack not found with name: %s", resource.getName()));
        }
    }

    private void deleteKeyPair(AuthenticatedContext authenticatedContext, OSClient client) {
        KeystoneCredentialView keystoneCredential = openStackClient.createKeystoneCredential(authenticatedContext);
        String keyPairName = keystoneCredential.getKeyPairName();
        client.compute().keypairs().delete(keyPairName);
        LOGGER.info("Keypair has been deleted: {}", keyPairName);
    }

    @Override
    public List<CloudResourceStatus> upscale(AuthenticatedContext authenticatedContext, CloudStack stack, List<CloudResource> resources) {
        String stackName = utils.getStackName(authenticatedContext);
        NeutronNetworkView neutronNetworkView = new NeutronNetworkView(stack.getNetwork());
        boolean existingNetwork = neutronNetworkView.isExistingNetwork();
        String existingSubnetCidr = getExistingSubnetCidr(authenticatedContext, stack);

        ModelContext modelContext = new ModelContext();
        modelContext.withExistingNetwork(existingNetwork);
        modelContext.withExistingSubnet(existingSubnetCidr != null);
        modelContext.withGroups(stack.getGroups());
        modelContext.withInstanceUserData(stack.getImage());
        modelContext.withLocation(authenticatedContext.getCloudContext().getLocation());
        modelContext.withStackName(stackName);
        modelContext.withNeutronNetworkView(neutronNetworkView);
        modelContext.withTemplateString(stack.getTemplate());
        modelContext.withTags(stack.getTags());

        String heatTemplate = heatTemplateBuilder.build(modelContext);
        Map<String, String> parameters = heatTemplateBuilder.buildParameters(
                authenticatedContext, stack, existingNetwork, existingSubnetCidr);
        return updateHeatStack(authenticatedContext, resources, heatTemplate, parameters);
    }

    @Override
    public Object collectResourcesToRemove(AuthenticatedContext authenticatedContext, CloudStack stack,
            List<CloudResource> resources, List<CloudInstance> vms) {
        return null;
    }

    @Override
    public List<CloudResourceStatus> downscale(AuthenticatedContext authenticatedContext, CloudStack cloudStack, List<CloudResource> resources,
            List<CloudInstance> vms, Object resourcesToRemove) {
        CloudStack stack = removeDeleteRequestedInstances(cloudStack);
        String stackName = utils.getStackName(authenticatedContext);
        NeutronNetworkView neutronNetworkView = new NeutronNetworkView(stack.getNetwork());
        boolean existingNetwork = neutronNetworkView.isExistingNetwork();
        String existingSubnetCidr = getExistingSubnetCidr(authenticatedContext, stack);

        ModelContext modelContext = new ModelContext();
        modelContext.withExistingNetwork(existingNetwork);
        modelContext.withExistingSubnet(existingSubnetCidr != null);
        modelContext.withGroups(stack.getGroups());
        modelContext.withInstanceUserData(stack.getImage());
        modelContext.withLocation(authenticatedContext.getCloudContext().getLocation());
        modelContext.withStackName(stackName);
        modelContext.withNeutronNetworkView(neutronNetworkView);
        modelContext.withTemplateString(stack.getTemplate());
        modelContext.withTags(stack.getTags());

        String heatTemplate = heatTemplateBuilder.build(modelContext);
        Map<String, String> parameters = heatTemplateBuilder.buildParameters(
                authenticatedContext, stack, existingNetwork, existingSubnetCidr);
        return updateHeatStack(authenticatedContext, resources, heatTemplate, parameters);
    }

    @Override
    public TlsInfo getTlsInfo(AuthenticatedContext authenticatedContext, CloudStack cloudStack) {
        return new TlsInfo(false);
    }

    @Override
    public String getStackTemplate() throws TemplatingDoesNotSupportedException {
        return heatTemplateBuilder.getTemplate();
    }

    @Override
    public List<CloudResourceStatus> update(AuthenticatedContext authenticatedContext, CloudStack stack, List<CloudResource> resources) {
        String stackName = utils.getStackName(authenticatedContext);
        NeutronNetworkView neutronNetworkView = new NeutronNetworkView(stack.getNetwork());
        boolean existingNetwork = neutronNetworkView.isExistingNetwork();
        String existingSubnetCidr = getExistingSubnetCidr(authenticatedContext, stack);

        ModelContext modelContext = new ModelContext();
        modelContext.withExistingNetwork(existingNetwork);
        modelContext.withExistingSubnet(existingSubnetCidr != null);
        modelContext.withGroups(stack.getGroups());
        modelContext.withInstanceUserData(stack.getImage());
        modelContext.withLocation(authenticatedContext.getCloudContext().getLocation());
        modelContext.withStackName(stackName);
        modelContext.withNeutronNetworkView(neutronNetworkView);
        modelContext.withTemplateString(stack.getTemplate());
        modelContext.withTags(stack.getTags());

        String heatTemplate = heatTemplateBuilder.build(modelContext);
        Map<String, String> parameters = heatTemplateBuilder.buildParameters(
                authenticatedContext, stack, existingNetwork, existingSubnetCidr);
        return updateHeatStack(authenticatedContext, resources, heatTemplate, parameters);
    }

    private List<CloudResourceStatus> updateHeatStack(AuthenticatedContext authenticatedContext, List<CloudResource> resources, String heatTemplate,
            Map<String, String> parameters) {
        CloudResource resource = utils.getHeatResource(resources);
        String stackName = utils.getStackName(authenticatedContext);
        String heatStackId = resource.getName();

        OSClient client = openStackClient.createOSClient(authenticatedContext);
        StackUpdate updateRequest = Builders.stackUpdate().template(heatTemplate)
                .parameters(parameters).timeoutMins(OPERATION_TIMEOUT).build();
        client.heat().stacks().update(stackName, heatStackId, updateRequest);
        LOGGER.info("Heat stack update request sent with stack name: '{}' for Heat stack: '{}'", stackName, heatStackId);
        return check(authenticatedContext, resources);
    }

    private CloudStack removeDeleteRequestedInstances(CloudStack stack) {
        List<Group> groups = new ArrayList<>(stack.getGroups().size());
        for (Group group : stack.getGroups()) {
            List<CloudInstance> instances = new ArrayList<>(group.getInstances());
            for (CloudInstance instance : group.getInstances()) {
                if (InstanceStatus.DELETE_REQUESTED == instance.getTemplate().getStatus()) {
                    instances.remove(instance);
                }
            }
            groups.add(new Group(group.getName(), group.getType(), instances, group.getSecurity(), null, stack.getInstanceAuthentication(),
                    stack.getInstanceAuthentication().getLoginUserName(), stack.getInstanceAuthentication().getPublicKey()));
        }
        return new CloudStack(groups, stack.getNetwork(), stack.getImage(), stack.getParameters(), stack.getTags(), stack.getTemplate(),
                stack.getInstanceAuthentication(), stack.getInstanceAuthentication().getLoginUserName(), stack.getInstanceAuthentication().getPublicKey());
    }

    private String getExistingSubnetCidr(AuthenticatedContext authenticatedContext, CloudStack stack) {
        NeutronNetworkView neutronView = new NeutronNetworkView(stack.getNetwork());
        return neutronView.isExistingSubnet() ? utils.getExistingSubnetCidr(authenticatedContext, neutronView) : null;
    }

}
