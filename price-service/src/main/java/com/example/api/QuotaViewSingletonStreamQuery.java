package com.example.api;

import akka.NotUsed;
import akka.javasdk.client.ComponentClient;
import akka.stream.Materializer;
import akka.stream.RestartSettings;
import akka.stream.javadsl.*;
import com.example.application.QuotaView;
import com.example.domain.Quota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class QuotaViewSingletonStreamQuery {

    private static final Logger logger = LoggerFactory.getLogger(QuotaViewSingletonStreamQuery.class);
    private final ComponentClient componentClient;
    private final Materializer materializer;

    private final Source<Quota, NotUsed> source;

    public QuotaViewSingletonStreamQuery(ComponentClient componentClient, Materializer materializer) {
        this.componentClient = componentClient;
        this.materializer = materializer;
        source = runStream();
    }

    public Source<Quota, NotUsed> getSource() {
        return source;
    }

    private Source<Quota, NotUsed> runStream() {
        var source = streamAllQuotas();
        return source
                .toMat(BroadcastHub.of(Quota.class), Keep.both())
                .run(materializer)
                .second();
    }

    private Source<Quota,?> streamAllQuotas(){
        return RestartSource.withBackoff(
                RestartSettings.create(Duration.ofMillis(1000),Duration.ofMillis(100),2.4),
                () -> {
                    logger.warn("Restart");
                    return componentClient.forView()
                            .stream(QuotaView::streamAll)
                            .source()
                            .mapConcat(qe -> {
                                logger.warn("streamAllQuotas: {}", qe.ccyPair());
                                var quotas = qe.quotas().stream().map(pq -> new Quota(pq.quotaId(), qe.priceRate().priceRateId(), pq.clientId(),qe.ccyPair(),qe.priceRate().tenor(), qe.priceRate().bid(), qe.priceRate().ask(),pq.creditStatus(),qe.priceRate().timestamp())).toList();
                                return quotas;
                            });
                }
        );
    }

}
