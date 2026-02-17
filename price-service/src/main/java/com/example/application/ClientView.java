package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import com.example.domain.ClientWorkflowState;
import com.example.domain.CreditStatus;

import java.util.List;

@Component(id = "client-view")
public class ClientView extends View {

  public record ClientEntry(String clientId, CreditStatus creditStatus) {}

  public record ClientEntries(List<ClientEntry> entries) {}

  @Consume.FromWorkflow(ClientWorkflow.class)
  public static class ClientUpdater extends TableUpdater<ClientEntry> {

    public Effect<ClientEntry> onUpdate(ClientWorkflowState state) {
      return effects().updateRow(
          new ClientEntry(state.clientId(), state.creditStatus()));
    }
  }

  @Query("SELECT * FROM clients WHERE clientId = ANY(:clientIds)")
  public QueryStreamEffect<ClientEntry> getByClientIds(List<String> clientIds) {
    return queryStreamResult();
  }

  @Query("SELECT * FROM clients WHERE clientId = :clientId")
  public QueryEffect<ClientEntry> getByClientId(String clientId) {
    return queryResult();
  }
}