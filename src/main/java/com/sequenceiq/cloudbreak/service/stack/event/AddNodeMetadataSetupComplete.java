package com.sequenceiq.cloudbreak.service.stack.event;

import java.util.HashSet;
import java.util.Set;

import com.sequenceiq.cloudbreak.domain.CloudPlatform;
import com.sequenceiq.cloudbreak.domain.Resource;
import com.sequenceiq.cloudbreak.service.stack.event.domain.CoreInstanceMetaData;

public class AddNodeMetadataSetupComplete extends ProvisionEvent {
    private Set<CoreInstanceMetaData> coreInstanceMetaData = new HashSet<>();
    private Set<Resource> resources = new HashSet<>();

    public AddNodeMetadataSetupComplete(CloudPlatform cloudPlatform, Long stackId, Set<CoreInstanceMetaData> coreInstanceMetaData, Set<Resource> resources) {
        super(cloudPlatform, stackId);
        this.coreInstanceMetaData = coreInstanceMetaData;
        this.resources = resources;
    }

    public Set<CoreInstanceMetaData> getCoreInstanceMetaData() {
        return coreInstanceMetaData;
    }

    public void setCoreInstanceMetaData(Set<CoreInstanceMetaData> coreInstanceMetaData) {
        this.coreInstanceMetaData = coreInstanceMetaData;
    }

    public Set<Resource> getResources() {
        return resources;
    }

    public void setResources(Set<Resource> resources) {
        this.resources = resources;
    }

}
