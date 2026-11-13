# 前端镜像：Node 构建静态资源，Nginx 承载并反代 /api
FROM node:22-alpine AS build
WORKDIR /build
COPY package*.json ./
RUN npm install --registry=https://registry.npmmirror.com
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
