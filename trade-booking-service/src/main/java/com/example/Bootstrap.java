package com.example;

import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.http.HttpClientProvider;
import com.example.client.AutoHedgerServiceClient;
import com.example.client.AutoHedgerServiceClientStub;
import com.example.client.PricingServiceClient;
import com.example.client.PricingServiceClientImpl;
import com.example.client.PricingServiceClientStub;
import com.typesafe.config.Config;

@Setup
public class Bootstrap implements ServiceSetup {

  private final Config config;
  private final HttpClientProvider httpClientProvider;

  public Bootstrap(Config config, HttpClientProvider httpClientProvider) {
    this.config = config;
    this.httpClientProvider = httpClientProvider;
  }

  @Override
  public DependencyProvider createDependencyProvider() {
    var autoHedgerServiceClient = new AutoHedgerServiceClientStub();
    PricingServiceClient pricingServiceClient;
    if (config.getBoolean("integration.test")) {
      pricingServiceClient = new PricingServiceClientStub();
    } else {
      pricingServiceClient = new PricingServiceClientImpl(httpClientProvider);
    }
    return new DependencyProvider() {
      @SuppressWarnings("unchecked")
      @Override
      public <T> T getDependency(Class<T> clazz) {
        if (clazz == AutoHedgerServiceClient.class) {
          return (T) autoHedgerServiceClient;
        }
        if (clazz == PricingServiceClient.class) {
          return (T) pricingServiceClient;
        }
        throw new RuntimeException("No such dependency: " + clazz);
      }
    };
  }
}
