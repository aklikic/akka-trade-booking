package com.example.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.http.HttpResponses;

@HttpEndpoint("/ui")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class UIEndpoint {

    @Get("/")
    public HttpResponse index() {
        return HttpResponses.staticResource("index.html");
    }
}