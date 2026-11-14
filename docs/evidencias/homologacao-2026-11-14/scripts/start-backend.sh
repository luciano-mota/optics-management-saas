#!/bin/bash
# Backend local isolado para a auditoria (banco optics_audit, sem e-mail, sem Asaas).
pkill -f "optics-backend-0.1.0-SNAPSHOT.jar" ; sleep 2
cd /home/user/optics-management-saas/backend
DB_NAME=optics_audit DB_USERNAME=optics DB_PASSWORD=local-dev-only \
OPTICS_EMAIL_VERIFICATION_REQUIRED=false PLATFORM_ADMIN_EMAIL=operador@lcm.test PLATFORM_ADMIN_PASSWORD=SenhaForte2026x \
BREVO_API_KEY= SPRING_MAIL_USERNAME= SPRING_MAIL_PASSWORD= ASAAS_API_KEY= OPTICS_ASAAS_API_KEY= LANG=C.UTF-8 \
nohup java -jar target/optics-backend-0.1.0-SNAPSHOT.jar >> /tmp/claude-0/audit/backend.log 2>&1 &
for i in $(seq 1 60); do curl -s --noproxy '*' -o /dev/null localhost:8080/actuator/health && echo "backend pronto" && exit 0; sleep 2; done; echo "backend NÃO subiu"; exit 1
