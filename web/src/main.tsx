import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import "@fontsource/ibm-plex-sans/400.css";
import "@fontsource/ibm-plex-sans/500.css";
import "@fontsource/ibm-plex-sans/600.css";
import { App } from "./App";
import "./styles.css";

const cache = new QueryClient({
  defaultOptions: { queries: { retry: false, staleTime: 15000 } },
});
createRoot(document.getElementById("root")!).render(
  <QueryClientProvider client={cache}>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </QueryClientProvider>,
);
