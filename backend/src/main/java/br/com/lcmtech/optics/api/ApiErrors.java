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
    /**
     * Corpo em formato errado (JSON quebrado, tipo errado, ID que não é UUID). Nunca devolve a mensagem do Jackson, que
     * traz nomes internos de classes; só a mensagem de validação em português, quando a causa for uma.
     */
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,String>> unreadable(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        // Só repassa mensagem de validação escrita por nós (lançada no pacote do sistema), nunca a do Java/Jackson.
        boolean ours = cause instanceof IllegalArgumentException && cause.getMessage() != null
                && cause.getStackTrace().length > 0 && cause.getStackTrace()[0].getClassName().startsWith("br.com.lcmtech.");
        String msg = ours ? cause.getMessage() : "Dados em formato inválido. Confira os campos e tente de novo.";
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<Map<String,String>> tooMany(TooManyAttemptsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "900").body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String,String>> forbidden(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }
    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ResponseEntity<Map<String,String>> notFound(java.util.NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage() == null ? "Registro não encontrado." : e.getMessage()));
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
