package br.com.lcmtech.optics.api;

/** Limite de tentativas atingido (ex.: códigos de verificação errados): a API responde 429. */
public class TooManyAttemptsException extends IllegalStateException {
    public TooManyAttemptsException(String message) {
        super(message);
    }
}
