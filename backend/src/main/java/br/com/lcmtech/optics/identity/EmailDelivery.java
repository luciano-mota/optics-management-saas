package br.com.lcmtech.optics.identity;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Mensagens transacionais por SMTP se configurado. A ausência do provedor
 * nunca gera uma falsa alegação de e-mail enviado.
 */
@Service
public class EmailDelivery {
    private final ObjectProvider<JavaMailSender> providers;
    @Value("${optics.mail.from:nao-responda@lcmtech.com.br}") private String from;
    @Value("${spring.mail.host:}") private String host;
    @Value("${spring.mail.username:}") private String username;
    @Value("${spring.mail.password:}") private String password;
    public EmailDelivery(ObjectProvider<JavaMailSender> providers) {this.providers=providers;}
    public boolean available(){
        return providers.getIfAvailable()!=null && !host.isBlank()
                && !username.isBlank() && !password.isBlank();
    }
    public void send(String to,String subject,String body) {
        if(!available())throw new IllegalStateException(
                "O envio de e-mails ainda não foi configurado no ambiente.");
        JavaMailSender sender=providers.getObject();
        SimpleMailMessage message=new SimpleMailMessage();
        message.setFrom(from);message.setTo(to);
        message.setSubject(subject);message.setText(body);
        sender.send(message);
    }
}
