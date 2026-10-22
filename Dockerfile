FROM oven/bun:1
WORKDIR /app
COPY index.tsx package.json ./
CMD ["bun", "index.tsx"]
