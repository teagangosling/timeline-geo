# Standalone image for the public read-only stats view. Build context is
# public-app/ itself - unlike the retired app's Dockerfile, which built from
# the repo root because the private and public apps shared one image and one
# set of Python deps. Nothing here is shared with GeoPulse's Quarkus backend,
# so the context stays narrow.
FROM python:3.12-slim

WORKDIR /srv

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY public-web /public-web

EXPOSE 8000

# No migrations are run at startup and none can be: this container connects
# as a SELECT-only role. Flyway (in the GeoPulse backend, as the read-write
# user) owns the schema.
#
# DATABASE_URL and PUBLIC_USER_ID are mandatory and are validated at import
# time - see app/db.py. The container is meant to crash on a missing value
# rather than fall back to anything.
CMD ["uvicorn", "app.public_main:app", "--host", "0.0.0.0", "--port", "8000"]
