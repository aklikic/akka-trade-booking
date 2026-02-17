package com.example.client;

public interface CreditCheckService {

  void subscribe(String clientId);

  void unsubscribe(String clientId);
}