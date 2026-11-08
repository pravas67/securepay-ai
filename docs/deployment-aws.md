# Deploying to AWS

A deliberately small, low-cost production setup: one EC2 instance running the two containers, Amazon RDS for
PostgreSQL, secrets in AWS Secrets Manager, logs in CloudWatch, HTTPS at an Application Load Balancer.

```
Internet ──HTTPS──▶ ALB (ACM certificate) ──HTTP:80──▶ EC2 (nginx + Spring Boot containers) ──5432──▶ RDS PostgreSQL
                                                      │
                                                      ├── instance role ──▶ Secrets Manager (read one secret)
                                                      ├──────────────────▶ CloudWatch Logs
                                                      └──────────────────▶ EC2 Describe + Cost Explorer (read-only)
```

> Rough cost while running (ap-south-1, on-demand): t3.small ≈ $15/month, db.t4g.micro ≈ $13/month,
> ALB ≈ $18/month, plus a few cents for Secrets Manager, logs, and $0.01 per Cost Explorer request.
> Stop or delete everything when you're done (see the last section).

The commands use the AWS CLI v2. Replace `ap-south-1` with your region throughout.

## 1. Network and security groups

Use the default VPC or your own. Create three security groups:

| Group | Inbound |
|---|---|
| `securepay-alb` | 443 from `0.0.0.0/0` |
| `securepay-app` | 80 from `securepay-alb` only (and optionally 22 from your IP, or use SSM Session Manager instead) |
| `securepay-db` | 5432 from `securepay-app` only |

## 2. Database (RDS)

```bash
aws rds create-db-instance \
  --db-instance-identifier securepay-db \
  --engine postgres --engine-version 16 \
  --db-instance-class db.t4g.micro --allocated-storage 20 \
  --db-name securepay \
  --master-username securepay_admin --manage-master-user-password \
  --vpc-security-group-ids <securepay-db-sg-id> \
  --no-publicly-accessible --storage-encrypted --backup-retention-period 7
```

Connect once (from the EC2 instance, after step 5) and create the application role:

```sql
CREATE ROLE securepay_app LOGIN PASSWORD '<long random password>';
GRANT ALL ON DATABASE securepay TO securepay_app;
\c securepay
GRANT ALL ON SCHEMA public TO securepay_app;
```

The app runs Flyway on startup, so this role creates the tables. For stricter separation, run Flyway with
an owner role and give the app a role without `TRUNCATE` (see [security.md](security.md)).

## 3. Secrets

Fill in [infra/aws/secret.example.json](../infra/aws/secret.example.json), then:

```bash
aws secretsmanager create-secret --name securepay/prod --secret-string file://secret.json
rm secret.json
```

`DB_URL` must include `?sslmode=require`. Use Stripe **test** keys; the app refuses live keys.
`BOOTSTRAP_ADMIN_*` creates your first admin on first start. Remove both keys from the secret afterwards.

## 4. Logs and IAM

```bash
aws logs create-log-group --log-group-name /securepay/prod
aws logs put-retention-policy --log-group-name /securepay/prod --retention-in-days 30
```

Create an IAM role for EC2 named `securepay-app`. Attach the inline policy from
[infra/aws/iam-instance-role-policy.json](../infra/aws/iam-instance-role-policy.json), replacing `REGION`
and `ACCOUNT_ID`. Optionally also attach `AmazonSSMManagedInstanceCore`, so you can open a shell without SSH.

The role can only read EC2 instance descriptions, read Cost Explorer, read this one secret, and write logs.
It can't start, stop or modify anything.

## 5. Instance

Push this repository to GitHub, then edit the three variables at the top of
[infra/aws/user-data.sh](../infra/aws/user-data.sh) and launch:

```bash
aws ec2 run-instances \
  --image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
  --instance-type t3.small \
  --iam-instance-profile Name=securepay-app \
  --security-group-ids <securepay-app-sg-id> \
  --metadata-options HttpTokens=required \
  --user-data file://infra/aws/user-data.sh \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=securepay-app}]'
```

`HttpTokens=required` enforces IMDSv2, which protects the instance-role credentials from SSRF.

First boot installs Docker, writes `/opt/securepay/.env` from Secrets Manager (mode 600), builds both images
and starts them. Follow progress in `/var/log/cloud-init-output.log`.

## 6. HTTPS (ALB)

1. Request a certificate in ACM for your domain and validate it via DNS.
2. Create a target group (HTTP, port 80, health check path `/`) and register the instance.
3. Create an internet-facing ALB in `securepay-alb` with an HTTPS:443 listener using the certificate,
   forwarding to the target group. Optionally add an HTTP:80 listener that redirects to HTTPS.
4. Point your domain at the ALB (Route 53 alias or a CNAME).

## 7. Stripe webhooks

In the Stripe dashboard (test mode), go to Developers → Webhooks → Add endpoint:

- URL: `https://<your-domain>/api/webhooks/stripe`
- Events: `payment_intent.succeeded`, `payment_intent.payment_failed`, `payment_intent.canceled`

Put the signing secret (`whsec_…`) in the `STRIPE_WEBHOOK_SECRET` field of the secret, then restart the app
(see below).

## 8. Monitoring

- **Logs:** CloudWatch Logs → `/securepay/prod`. Backend logs are JSON (ECS format), so Logs Insights can
  query them, for example:
  `fields @timestamp, message | filter log.level = "ERROR" | sort @timestamp desc`
- **Alarms (recommended):**
  - a metric filter on `log.level = "ERROR"` with an alarm at ≥ 5 in 5 minutes;
  - the ALB `UnHealthyHostCount` ≥ 1;
  - RDS `FreeStorageSpace` and `CPUUtilization`.
- **Audit:** the `audit_log` table is the business-level record (who prepared, approved, was denied what).

## Updating

```bash
# on the instance (SSM Session Manager or SSH)
cd /opt/securepay/app && sudo git pull
cd infra/aws && sudo AWS_REGION=ap-south-1 docker compose -f docker-compose.prod.yml up -d --build
```

After changing the secret, rewrite the env file and restart:

```bash
sudo bash -c 'aws secretsmanager get-secret-value --secret-id securepay/prod --query SecretString --output text \
  | jq -r "to_entries[] | \"\(.key)=\(.value)\"" > /opt/securepay/.env'
cd /opt/securepay/app/infra/aws && sudo AWS_REGION=ap-south-1 docker compose -f docker-compose.prod.yml up -d
```

## Tearing down

Delete, in this order: the ALB and target group, the EC2 instance, the RDS instance (take a final snapshot if
you want one), the secret (`--force-delete-without-recovery` to skip the 7-day wait), the log group, the IAM
role, and the security groups.
