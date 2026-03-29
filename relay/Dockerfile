FROM node:22-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

COPY push-service.js ./push-service.js
COPY relay.js ./relay.js
COPY server.js ./server.js

EXPOSE 8787

CMD ["npm", "start"]
