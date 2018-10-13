package com.sequenceiq.cloudbreak.controller.validation.credential;

import com.sequenceiq.cloudbreak.controller.exception.BadRequestException;
import com.sequenceiq.cloudbreak.service.account.EnabledCloudPlatformService;
import org.springframework.stereotype.Component;

import javax.inject.Inject;

@Component
public class CredentialValidator {

    @Inject
    private EnabledCloudPlatformService enabledCloudPlatformService;

    public void validateCredentialCloudPlatform(String cloudPlatform) {
        if (!enabledCloudPlatformService.enabledPlatforms().contains(cloudPlatform)) {
            throw new BadRequestException(String.format("There is no such cloud platform as '%s'", cloudPlatform));
        }
    }

}
