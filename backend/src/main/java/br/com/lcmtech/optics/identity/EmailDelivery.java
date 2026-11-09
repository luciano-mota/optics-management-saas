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
    public EmailDelivery(ObjectProvider<JavaMailSender> providers) {this.providers=providers;}
    public boolean available(){return providers.getIfAvailable()!=null;}
    public void send(String to,String subject,String body) {
        JavaMailSender sender=providers.getIfAvailable();
        if(sender==null)throw new IllegalStateException(
                "SMTP não configurado. Solicite um convite manual ao administrador.");
        SimpleMailMessage message=new SimpleMailMessage();
        message.setFrom(from);message.setTo(to);
        message.setSubject(subject);message.setText(body);
        sender.send(message);
    }
}
