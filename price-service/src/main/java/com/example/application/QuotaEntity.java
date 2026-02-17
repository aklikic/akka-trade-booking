package com.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import com.example.domain.PriceRate;
import com.example.domain.PriceRateClientQuota;
import com.example.domain.QuotaState;
import com.example.domain.Quota;

import java.util.List;
import java.util.Optional;

@Component(id = "price-rate-entity")
public class QuotaEntity extends KeyValueEntity<QuotaState> {

  public record AddCommand(String ccyPair, PriceRate priceRate, List<PriceRateClientQuota> quotas) {}

  public Effect<Done> add(AddCommand command) {
    var state = new QuotaState(command.ccyPair(), command.priceRate(), command.quotas());
    return effects()
        .updateState(state)
        .thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<Optional<Quota>> get(String clientId) {
    if (currentState() == null) {
      return effects().reply(Optional.empty());
    }
    return effects().reply(currentState().quotas().stream()
        .filter(q -> q.clientId().equals(clientId))
        .findFirst()
            .map(pq -> new Quota(pq.quotaId(), currentState().priceRate().priceRateId(), pq.clientId(), currentState().ccyPair(), currentState().priceRate().tenor(), currentState().priceRate().bid(), currentState().priceRate().ask(), pq.creditStatus(),currentState().priceRate().timestamp())));
  }
}