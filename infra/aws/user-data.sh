#!/bin/bash
# EC2 user data for Amazon Linux 2023. Runs once at first boot as root.
# Set these three values before launching the instance:
REPO_URL="https://github.com/YOUR_USER/securepay-ai.git"
SECRET_ID="securepay/prod"
AWS_REGION="ap-south-1"

set -euo pipefail
export AWS_REGION

dnf install -y docker git jq
systemctl enable --now docker
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-$(uname -m)" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# Secrets: fetched with the instance role (no keys on disk except this root-only env file).
install -d -m 700 /opt/securepay
aws secretsmanager get-secret-value --secret-id "$SECRET_ID" --query SecretString --output text \
  | jq -r 'to_entries[] | "\(.key)=\(.value)"' > /opt/securepay/.env
chmod 600 /opt/securepay/.env

git clone --depth 1 "$REPO_URL" /opt/securepay/app
cd /opt/securepay/app/infra/aws
docker compose -f docker-compose.prod.yml up -d --build
