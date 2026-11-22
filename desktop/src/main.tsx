import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import "./styles.css";
import { readPreferences, resolveTheme } from "./enhancements/hooks";

document.documentElement.dataset.theme = resolveTheme(readPreferences().theme);

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
