/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.api.util;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.net.URI;

@Provider
@PreMatching
public class EnginePathRedirectFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        URI requestUri = requestContext.getUriInfo().getRequestUri();
        String path = requestUri.getPath();

        if (path != null && path.startsWith("/engine.svc")) {
            String rest = path.substring("/engine.svc".length());
            String newPath = "/Engine.svc" + rest;
            URI newUri = UriBuilder.fromUri(requestUri)
                    .replacePath(newPath)
                    .build();
            requestContext.abortWith(Response.status(308).location(newUri).build());
        }
    }
}