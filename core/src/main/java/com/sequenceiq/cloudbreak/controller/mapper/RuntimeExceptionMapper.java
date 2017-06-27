package com.sequenceiq.cloudbreak.controller.mapper;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

import com.sequenceiq.cloudbreak.controller.json.ExceptionResult;

@Provider
public class RuntimeExceptionMapper extends BaseExceptionMapper<RuntimeException> {

    @Override
    protected Object getEntity(RuntimeException exception) {
        return new ExceptionResult("Internal server error: " + exception.getMessage());
    }

    @Override
    Response.Status getResponseStatus() {
        return Response.Status.INTERNAL_SERVER_ERROR;
    }
}