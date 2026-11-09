package br.com.lcmtech.optics.api;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String,String>> forbidden(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class,
            DataIntegrityViolationException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String,String>> invalid(Exception e) {
        String msg = e instanceof DataIntegrityViolationException ?
                "Não foi possível salvar: confira se o registro já existe e se os dados estão corretos." :
                e instanceof MethodArgumentNotValidException ? "Revise os campos obrigatórios." : e.getMessage();
        return ResponseEntity.badRequest().body(Map.of("error",msg == null ? "Dados inválidos." : msg));
    }
}
