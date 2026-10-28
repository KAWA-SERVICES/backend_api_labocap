package com.labo.anapath.common.notification;

import com.labo.anapath.common.exception.InvalidOperationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OurVoiceClient {

    private final RestTemplate restTemplate;

    /** Envoie un SMS via OurVoice. */
    public void sms(String endpoint, String accessToken, String to, String messageBody) {
        try {
            HttpHeaders headers = buildHeaders(accessToken);
            Map<String, Object> body = Map.of(
                    "to", List.of(to),
                    "body", messageBody,
                    "sender_id", "c7e219bb-aa98-49e4-a87d-71250babaf98");
            ResponseEntity<Map> response = restTemplate.exchange(
                    endpoint, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new InvalidOperationException("Erreur OurVoice lors de l'envoi SMS");
            }
        } catch (RestClientException e) {
            log.error("OurVoice SMS failed: {}", e.getMessage());
            throw new InvalidOperationException("Erreur OurVoice lors de l'envoi SMS: " + e.getMessage());
        }
    }

    private HttpHeaders buildHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }
}
