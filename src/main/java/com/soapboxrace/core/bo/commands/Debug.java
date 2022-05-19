package com.soapboxrace.core.bo.commands;

import javax.ws.rs.core.Response;

public class Debug {
    public Response Commands() {
        return Response.noContent().build();
	}
}