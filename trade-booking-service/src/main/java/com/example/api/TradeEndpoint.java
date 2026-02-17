package com.example.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import com.example.application.TradeBookingWorkflow;
import com.example.application.TradesByClientView;
import com.example.client.PricingServiceClient;
import com.example.domain.TradeBookingState;

@HttpEndpoint("/trades")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class TradeEndpoint {

  public record AcceptRequest(String quotaId, String priceRateId, String clientId, String side, double quantity) {}

  public record TradeResponse(
      String tradeId,
      String quotaId,
      String status,
      String preTradeResult) {

    static TradeResponse fromState(TradeBookingState state) {
      return new TradeResponse(
          state.tradeId(),
          state.quota().quotaId(),
          state.status().name(),
          state.preTradeResult() != null ? state.preTradeResult().name() : null);
    }
  }

  private final ComponentClient componentClient;
  private final PricingServiceClient pricingServiceClient;

  public TradeEndpoint(ComponentClient componentClient, PricingServiceClient pricingServiceClient) {
    this.componentClient = componentClient;
    this.pricingServiceClient = pricingServiceClient;
  }

  @Post("/accept")
  public HttpResponse accept(AcceptRequest request) {
    var quota = pricingServiceClient.getQuota(request.clientId(), request.priceRateId(), request.quotaId())
        .orElseThrow(() -> new IllegalArgumentException("Quota not found for priceRateId: " + request.priceRateId()));

    var tradeId = TradeBookingWorkflow.tradeId(request.clientId(), request.quotaId());
    componentClient.forWorkflow(tradeId)
        .method(TradeBookingWorkflow::acceptQuote)
        .invoke(new TradeBookingWorkflow.AcceptQuoteCommand(quota, request.side(), request.quantity()));

    return HttpResponses.ok();
  }

  @Get("/{tradeId}/notifications")
  public HttpResponse notifications(String tradeId) {
    return HttpResponses.serverSentEvents(
        componentClient
            .forWorkflow(tradeId)
            .notificationStream(TradeBookingWorkflow::updates)
            .source()
    );
  }

  @Get("/by-client/{clientId}/updates")
  public HttpResponse streamByClient(String clientId) {
    return HttpResponses.serverSentEvents(
        componentClient.forView()
            .stream(TradesByClientView::streamByClientId)
            .source(clientId)
    );
  }

  @Get("/{tradeId}")
  public TradeResponse getByTradeId(String tradeId) {
    var state = componentClient.forWorkflow(tradeId)
        .method(TradeBookingWorkflow::getState)
        .invoke();

    return TradeResponse.fromState(state);
  }
}
