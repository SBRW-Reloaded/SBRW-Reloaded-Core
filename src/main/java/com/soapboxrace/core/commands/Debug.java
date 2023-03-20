package com.soapboxrace.core.commands;

import javax.ws.rs.core.Response;

public class Debug {
    public Response Commands() {
        return Response.noContent().build();
	}
}