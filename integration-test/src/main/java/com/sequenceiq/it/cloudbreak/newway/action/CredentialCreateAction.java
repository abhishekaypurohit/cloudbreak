package com.sequenceiq.it.cloudbreak.newway.action;

import static com.sequenceiq.it.cloudbreak.newway.log.Log.logJSON;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sequenceiq.it.cloudbreak.newway.CloudbreakClient;
import com.sequenceiq.it.cloudbreak.newway.CredentialEntity;
import com.sequenceiq.it.cloudbreak.newway.context.TestContext;
import com.sequenceiq.it.cloudbreak.newway.log.Log;

public class CredentialCreateAction implements ActionV2<CredentialEntity> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CredentialCreateAction.class);

    @Override
    public CredentialEntity action(TestContext testContext, CredentialEntity entity, CloudbreakClient client) throws Exception {
        Log.log(LOGGER, " post "
                .concat(entity.getName())
                .concat(" private credential. "));
        logJSON(LOGGER, " Credential post request:\n", entity.getRequest());
        try {
            entity.setResponse(
                    client.getCloudbreakClient()
                            .credentialV3Endpoint()
                            .createInWorkspace(client.getWorkspaceId(), entity.getRequest()));
            logJSON(LOGGER, " Credential created successfully:\n", entity.getRequest());
        } catch (Exception e) {
            LOGGER.info("Creation of credential has failed, load from the CB");
            entity.setResponse(
                    client.getCloudbreakClient()
                            .credentialV3Endpoint()
                            .getByNameInWorkspace(client.getWorkspaceId(), entity.getRequest().getName()));
        }
        return entity;
    }
}
