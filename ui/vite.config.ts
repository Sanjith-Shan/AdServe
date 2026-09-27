import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Built into the server's static resources, served at /console/ next to /graphql.
export default defineConfig({
  plugins: [react()],
  base: "/console/",
  build: {
    outDir: "../server/src/main/resources/static/console",
    emptyOutDir: true,
  },
  server: {
    proxy: { "/graphql": "http://localhost:28080" },
  },
});
