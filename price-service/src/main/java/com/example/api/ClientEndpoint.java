package com.example.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.NotUsed;
import akka.stream.Materializer;
import akka.stream.javadsl.Merge;
import akka.stream.javadsl.Source;
import com.example.application.*;
import com.example.domain.ClientWorkflowState;
import com.example.domain.Quota;
import com.example.domain.CreditStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

@HttpEndpoint("/clients")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class ClientEndpoint {

  private final Logger logger = LoggerFactory.getLogger(ClientEndpoint.class);
  private final ComponentClient componentClient;
  private final Materializer materializer;
  private final QuotaViewSingletonStreamQuery  quotaViewSingletonStreamQuery;

  public ClientEndpoint(ComponentClient componentClient, Materializer materializer, QuotaViewSingletonStreamQuery quotaViewSingletonStreamQuery) {
    this.componentClient = componentClient;
    this.materializer = materializer;
    this.quotaViewSingletonStreamQuery = quotaViewSingletonStreamQuery;
  }

  @Post("/{clientId}/subscribe/{ccyPair}")
  public HttpResponse subscribe(String clientId, String ccyPair) {
    componentClient.forWorkflow(clientId)
            .method(ClientWorkflow::subscribe)
            .invoke(ccyPair);
    return HttpResponses.ok();
  }

  @Post("/{clientId}/unsubscribe/{ccyPair}")
  public HttpResponse unsubscribe(String clientId, String ccyPair) {
    componentClient.forWorkflow(clientId)
            .method(ClientWorkflow::unsubscribe)
            .invoke(ccyPair);
    return HttpResponses.ok();
  }

  @Get("/{clientId}/state")
  public ClientWorkflowState getState(String clientId) {
    return componentClient.forWorkflow(clientId)
        .method(ClientWorkflow::getState)
        .invoke();
  }

  @Get("/{clientId}/price-rate/{priceRateId}/quota")
  public Optional<Quota> getPriceRateQuota(String clientId, String priceRateId) {
    return componentClient.forKeyValueEntity(priceRateId)
        .method(QuotaEntity::get)
        .invoke(clientId);
  }

  @Get("/{clientId}/quotas")
  public HttpResponse quotasStream(String clientId) {
    return HttpResponses.serverSentEvents(
            quotaViewSingletonStreamQuery.getSource()
                    .map(q -> {
                      logger.info("Getting quota {}/{} for client {}", q.quotaId(), q.ccyPair(), clientId);
                      return q;
                    })
            .filter(q -> q.clientId().equals(clientId))
    );
  }

  public record RateUpdate(String ccyPair, String tenor, double bid, double ask, long seq, long tsMs, Optional<String> priceRateId) {
    public RateUpdate(String ccyPair, String tenor, double bid, double ask, long seq, long tsMs) {
      this(ccyPair, tenor, bid, ask, seq, tsMs, Optional.empty());
    }
  }

  public record CreditUpdate(String clientId, CreditStatus status) {}

  @Post("/simulate/rate-update")
  public HttpResponse simulateRateUpdate(RateUpdate rateUpdate) {
     FxRateConsumer.priceRateProcessing(componentClient, materializer, rateUpdate.ccyPair(), rateUpdate.tenor(), rateUpdate.bid(), rateUpdate.ask(), rateUpdate.seq(), rateUpdate.tsMs(), rateUpdate.priceRateId());
     return HttpResponses.ok();
  }

  @Post("/simulate/credit-update")
  public HttpResponse simulateCreditUpdate(CreditUpdate creditUpdate) {
    componentClient.forWorkflow(creditUpdate.clientId())
        .method(ClientWorkflow::creditCheckStatus)
        .invoke(new ClientWorkflow.CreditCheckUpdate(creditUpdate.status()));
    return HttpResponses.ok();
  }
}