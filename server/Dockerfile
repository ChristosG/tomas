FROM node:22-alpine

WORKDIR /app

# No dependencies at all - just the two source files and the manifest.
COPY package.json ./
COPY server.mjs store.mjs ./

ENV NODE_ENV=production \
    PORT=8787 \
    DATA_DIR=/data

# rows.jsonl and media/ live here. Mount a volume so they survive the container.
RUN mkdir -p /data && chown -R node:node /data
VOLUME ["/data"]
USER node

EXPOSE 8787

HEALTHCHECK --interval=60s --timeout=5s --start-period=5s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8787)+'/v1/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server.mjs"]
