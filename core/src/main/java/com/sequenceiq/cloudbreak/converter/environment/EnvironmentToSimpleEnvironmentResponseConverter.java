package com.sequenceiq.cloudbreak.converter.environment;

import org.springframework.stereotype.Component;

import com.sequenceiq.cloudbreak.api.model.environment.response.SimpleEnvironmentResponse;
import com.sequenceiq.cloudbreak.api.model.users.WorkspaceResourceResponse;
import com.sequenceiq.cloudbreak.converter.AbstractConversionServiceAwareConverter;
import com.sequenceiq.cloudbreak.domain.environment.Environment;

@Component
public class EnvironmentToSimpleEnvironmentResponseConverter extends AbstractConversionServiceAwareConverter<Environment, SimpleEnvironmentResponse> {

    @Override
    public SimpleEnvironmentResponse convert(Environment source) {
        SimpleEnvironmentResponse response = new SimpleEnvironmentResponse();
        response.setId(source.getId());
        response.setName(source.getName());
        response.setDescription(source.getDescription());
        response.setRegions(source.getRegionsSet());
        response.setCloudPlatform(source.getCloudPlatform());
        response.setWorkspace(getConversionService().convert(source.getWorkspace(), WorkspaceResourceResponse.class));
        return response;
    }
}
