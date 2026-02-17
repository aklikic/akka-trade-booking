package com.example.application;

import akka.Done;
import akka.javasdk.testkit.KeyValueEntityTestKit;
import com.example.domain.CreditStatus;
import com.example.domain.PriceRate;
import com.example.domain.PriceRateClientQuota;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class QuotaEntityTest {

  @Test
  public void shouldAddQuotas() {
    var testKit = KeyValueEntityTestKit.of("rate-1", QuotaEntity::new);
    var priceRate = new PriceRate("rate-1", "SPOT", 1.1050, 1.1055, 1, 1700000000000L);
    var quotas = List.of(
        new PriceRateClientQuota("q1", "client-1", CreditStatus.OK),
        new PriceRateClientQuota("q2", "client-2", CreditStatus.OK)
    );

    var result = testKit.method(QuotaEntity::add)
        .invoke(new QuotaEntity.AddCommand("EURUSD", priceRate, quotas));

    assertThat(result.isReply()).isTrue();
    assertThat(result.getReply()).isEqualTo(Done.getInstance());
    assertThat(testKit.getState().ccyPair()).isEqualTo("EURUSD");
    assertThat(testKit.getState().priceRate()).isEqualTo(priceRate);
    assertThat(testKit.getState().quotas()).hasSize(2);
  }

  @Test
  public void shouldGetQuotaByClientId() {
    var testKit = KeyValueEntityTestKit.of("rate-1", QuotaEntity::new);
    var priceRate = new PriceRate("rate-1", "SPOT", 1.1050, 1.1055, 1, 1700000000000L);
    var quotas = List.of(
        new PriceRateClientQuota("q1", "client-1", CreditStatus.OK),
        new PriceRateClientQuota("q2", "client-2", CreditStatus.FAIL)
    );
    testKit.method(QuotaEntity::add)
        .invoke(new QuotaEntity.AddCommand("EURUSD", priceRate, quotas));

    var result = testKit.method(QuotaEntity::get).invoke("client-1");

    assertThat(result.isReply()).isTrue();
    var quota = result.getReply();
    assertThat(quota).isPresent();
    assertThat(quota.get().quotaId()).isEqualTo("q1");
    assertThat(quota.get().priceRateId()).isEqualTo("rate-1");
    assertThat(quota.get().clientId()).isEqualTo("client-1");
    assertThat(quota.get().ccyPair()).isEqualTo("EURUSD");
    assertThat(quota.get().tenor()).isEqualTo("SPOT");
    assertThat(quota.get().bid()).isEqualTo(1.1050);
    assertThat(quota.get().ask()).isEqualTo(1.1055);
    assertThat(quota.get().creditStatus()).isEqualTo(CreditStatus.OK);
    assertThat(quota.get().timestamp()).isEqualTo(1700000000000L);
  }

  @Test
  public void shouldReturnEmptyForUnknownClientId() {
    var testKit = KeyValueEntityTestKit.of("rate-1", QuotaEntity::new);
    var priceRate = new PriceRate("rate-1", "SPOT", 1.1050, 1.1055, 1, 1700000000000L);
    var quotas = List.of(
        new PriceRateClientQuota("q1", "client-1", CreditStatus.OK)
    );
    testKit.method(QuotaEntity::add)
        .invoke(new QuotaEntity.AddCommand("EURUSD", priceRate, quotas));

    var result = testKit.method(QuotaEntity::get).invoke("client-unknown");

    assertThat(result.isReply()).isTrue();
    assertThat(result.getReply()).isEmpty();
  }

  @Test
  public void shouldReturnEmptyWhenNoState() {
    var testKit = KeyValueEntityTestKit.of("rate-1", QuotaEntity::new);

    var result = testKit.method(QuotaEntity::get).invoke("client-1");

    assertThat(result.isReply()).isTrue();
    assertThat(result.getReply()).isEmpty();
  }

  @Test
  public void shouldOverwriteOnSubsequentAdd() {
    var testKit = KeyValueEntityTestKit.of("rate-1", QuotaEntity::new);
    var priceRate1 = new PriceRate("rate-1", "SPOT", 1.1050, 1.1055, 1, 1700000000000L);
    var quotas1 = List.of(new PriceRateClientQuota("q1", "client-1", CreditStatus.OK));
    testKit.method(QuotaEntity::add)
        .invoke(new QuotaEntity.AddCommand("EURUSD", priceRate1, quotas1));

    var priceRate2 = new PriceRate("rate-1", "SPOT", 1.2000, 1.2005, 2, 1700000001000L);
    var quotas2 = List.of(
        new PriceRateClientQuota("q3", "client-1", CreditStatus.FAIL),
        new PriceRateClientQuota("q4", "client-3", CreditStatus.OK)
    );
    testKit.method(QuotaEntity::add)
        .invoke(new QuotaEntity.AddCommand("EURUSD", priceRate2, quotas2));

    assertThat(testKit.getState().priceRate().bid()).isEqualTo(1.2000);
    assertThat(testKit.getState().quotas()).hasSize(2);

    var result = testKit.method(QuotaEntity::get).invoke("client-1");
    assertThat(result.getReply()).isPresent();
    assertThat(result.getReply().get().bid()).isEqualTo(1.2000);
    assertThat(result.getReply().get().creditStatus()).isEqualTo(CreditStatus.FAIL);
  }
}