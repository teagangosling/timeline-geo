FROM alpine:3.20
RUN apk add --no-cache docker-cli docker-cli-compose git python3 bash openssh-client \
 && git config --global --add safe.directory /repo
# OpenSSH refuses to run at all if it can't resolve $HOME for the
# invoking uid (getpwuid fails), so the container's numeric uid (set via
# docker-compose's `user:`, to match the host user that owns the
# bind-mounted deploy key) needs an actual passwd entry, not just
# matching file ownership.
RUN adduser -D -u 1000 -h /home/deploy deploy
WORKDIR /app
COPY server.py deploy.sh ./
RUN chmod +x deploy.sh
EXPOSE 8000
CMD ["python3", "server.py"]
