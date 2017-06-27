package com.sequenceiq.cloudbreak.controller.mapper;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

import com.sequenceiq.cloudbreak.controller.json.ExceptionResult;

@Provider
public class DefaultExceptionMapper extends BaseExceptionMapper<Exception> {

    @Override
    protected Object getEntity(Exception exception) {
        return new ExceptionResult("Internal server error: " + exception.getMessage());
    }

    @Override
    Response.Status getResponseStatus() {
        return Response.Status.INTERNAL_SERVER_ERROR;
    }
}
