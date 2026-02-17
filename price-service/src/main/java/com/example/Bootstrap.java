package com.example;

import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
import akka.stream.Materializer;
import com.example.api.QuotaViewSingletonStreamQuery;
import com.example.application.*;
import com.example.client.CreditCheckService;
import com.example.client.CreditCheckServiceStub;
import com.example.client.FxRateService;
import com.example.client.FxRateServiceStub;
import com.typesafe.config.Config;

import java.util.Set;

@Setup
public class Bootstrap implements ServiceSetup {

  private final Config config;
  private final ComponentClient componentClient;
  private final Materializer materializer;
  public Bootstrap(Config config,  ComponentClient componentClient,  Materializer materializer) {
    this.config = config;
    this.componentClient = componentClient;
    this.materializer = materializer;
  }
  @Override
  public Set<Class<?>> disabledComponents() {
    System.out.println(config.getString("integration.test"));
    if(config.getBoolean("integration.test"))
      return Set.of();
    else
      return Set.of(FxRateConsumer.class, CreditCheckConsumer.class, PriceEntitySubscriptionsManagerConsumer.class);
  }

  @Override
  public DependencyProvider createDependencyProvider() {
    var creditCheckService = new CreditCheckServiceStub();
    var fxRateService = new FxRateServiceStub();
    var quotaViewSingletonStream = new QuotaViewSingletonStreamQuery(componentClient, materializer);
    return new DependencyProvider() {
      @SuppressWarnings("unchecked")
      @Override
      public <T> T getDependency(Class<T> clazz) {
        if (clazz == CreditCheckService.class) {
          return (T) creditCheckService;
        }
        if (clazz == FxRateService.class) {
          return (T) fxRateService;
        }
        if (clazz == QuotaViewSingletonStreamQuery.class) {
          return (T) quotaViewSingletonStream;
        }
        throw new RuntimeException("No such dependency: " + clazz);
      }
    };
  }
}