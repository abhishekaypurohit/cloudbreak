package com.sequenceiq.cloudbreak.converter.stack;

import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.convert.ConversionService;
import org.springframework.stereotype.Component;

import com.sequenceiq.cloudbreak.api.model.BlueprintViewResponse;
import com.sequenceiq.cloudbreak.api.model.SharedServiceResponse;
import com.sequenceiq.cloudbreak.api.model.stack.cluster.ClusterViewResponse;
import com.sequenceiq.cloudbreak.api.model.stack.cluster.HostGroupViewResponse;
import com.sequenceiq.cloudbreak.api.model.v2.AttachedClusterInfoResponse;
import com.sequenceiq.cloudbreak.converter.CompactViewToCompactViewResponseConverter;
import com.sequenceiq.cloudbreak.domain.KerberosConfig;
import com.sequenceiq.cloudbreak.domain.stack.Stack;
import com.sequenceiq.cloudbreak.domain.view.ClusterApiView;
import com.sequenceiq.cloudbreak.domain.view.HostGroupView;
import com.sequenceiq.cloudbreak.service.stack.StackService;

@Component
public class ClusterApiViewToClusterViewResponseConverter extends CompactViewToCompactViewResponseConverter<ClusterApiView, ClusterViewResponse> {
    @Inject
    @Qualifier("conversionService")
    private ConversionService conversionService;

    @Inject
    private StackService stackService;

    @Override
    public ClusterViewResponse convert(ClusterApiView source) {
        ClusterViewResponse clusterViewResponse = super.convert(source);
        clusterViewResponse.setAmbariServerIp(source.getAmbariIp());
        clusterViewResponse.setStatus(source.getStatus());
        convertKerberosConfig(source, clusterViewResponse);
        clusterViewResponse.setHostGroups(convertHostGroupsToJson(source.getHostGroups()));
        clusterViewResponse.setBlueprint(conversionService.convert(source.getBlueprint(), BlueprintViewResponse.class));
        addSharedServiceResponse(source, clusterViewResponse);
        return clusterViewResponse;
    }

    @Override
    protected ClusterViewResponse createTarget() {
        return new ClusterViewResponse();
    }

    private void convertKerberosConfig(ClusterApiView source, ClusterViewResponse clusterViewResponse) {
        KerberosConfig kerberosConfig = source.getKerberosConfig();
        if (source.getSecure() && kerberosConfig != null) {
            clusterViewResponse.setSecure(source.getSecure());
        }
    }

    private Set<HostGroupViewResponse> convertHostGroupsToJson(Iterable<HostGroupView> hostGroups) {
        Set<HostGroupViewResponse> jsons = new HashSet<>();
        for (HostGroupView hostGroup : hostGroups) {
            jsons.add(getConversionService().convert(hostGroup, HostGroupViewResponse.class));
        }
        return jsons;
    }

    private void addSharedServiceResponse(ClusterApiView cluster, ClusterViewResponse clusterResponse) {
        SharedServiceResponse sharedServiceResponse = new SharedServiceResponse();
        if (cluster.getStack().getDatalakeId() != null) {
            sharedServiceResponse.setSharedClusterId(cluster.getStack().getDatalakeId());
            sharedServiceResponse.setSharedClusterName(stackService.getByIdWithTransaction(cluster.getStack().getDatalakeId()).getName());
        } else {
            for (Stack stack : stackService.findClustersConnectedToDatalake(cluster.getStack().getId())) {
                AttachedClusterInfoResponse attachedClusterInfoResponse = new AttachedClusterInfoResponse();
                attachedClusterInfoResponse.setId(stack.getId());
                attachedClusterInfoResponse.setName(stack.getName());
                sharedServiceResponse.getAttachedClusters().add(attachedClusterInfoResponse);
            }
        }
        clusterResponse.setSharedServiceResponse(sharedServiceResponse);
    }

}
