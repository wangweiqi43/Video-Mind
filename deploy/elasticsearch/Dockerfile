# syntax=docker/dockerfile:1.7
ARG ELASTICSEARCH_VERSION=8.19.14
FROM docker.elastic.co/elasticsearch/elasticsearch:${ELASTICSEARCH_VERSION}

ARG ELASTICSEARCH_VERSION=8.19.14
ARG IK_PLUGIN_URL=https://get.infini.cloud/elasticsearch/analysis-ik/${ELASTICSEARCH_VERSION}
RUN set -eux; \
    mkdir -p /tmp/videomind-ik; \
    archive="/tmp/videomind-ik/analysis-ik-${ELASTICSEARCH_VERSION}.zip"; \
    curl --fail --location --show-error --silent \
      --retry 8 --retry-all-errors --retry-delay 3 --connect-timeout 20 \
      --output "${archive}" "${IK_PLUGIN_URL}"; \
    bin/elasticsearch-plugin install --batch "file://${archive}"; \
    bin/elasticsearch-plugin list | grep -Fx analysis-ik; \
    test "$(sed -n 's/^elasticsearch.version=//p' plugins/analysis-ik/plugin-descriptor.properties)" = "${ELASTICSEARCH_VERSION}"
