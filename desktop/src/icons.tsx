// Lynko icons — one consistent stroke, drawn for this app.
// 20x20 grid, stroke 1.6, round joins.

export const Mark = () => (
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <rect x="2.5" y="2.5" width="19" height="19" rx="6" fill="#20242A" stroke="rgba(255,255,255,0.12)" />
    <circle cx="8.4" cy="12" r="2.6" fill="#ffb454" />
    <circle cx="15.6" cy="12" r="2.6" fill="#f2f1ec" />
    <rect x="10.6" y="11.2" width="2.8" height="1.6" rx="0.8" fill="#ffb454" />
  </svg>
);

const ic = (path: React.ReactNode) => () => (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {path}
  </svg>
);

export const IconDevices = ic(
  <>
    <rect x="7" y="2.5" width="10" height="19" rx="2.5" />
    <path d="M10.5 18.5h3" />
  </>
);

export const IconScreen = ic(
  <>
    <rect x="2.5" y="4" width="19" height="12.5" rx="2" />
    <path d="M9 20.5h6M12 16.5v4" />
  </>
);

export const IconClip = ic(
  <>
    <rect x="8" y="3" width="12" height="13" rx="2" />
    <path d="M16 18v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h2" />
  </>
);

export const IconFiles = ic(
  <>
    <path d="M12 3v12" />
    <path d="M7 10l5 5 5-5" />
    <path d="M4 19.5h16" />
  </>
);

export const IconNotes = ic(
  <>
    <path d="M6 9V4.5a1.5 1.5 0 0 1 1.5-1.5h9L21 7v12.5a1.5 1.5 0 0 1-1.5 1.5h-9" />
    <path d="M15 3v5h6" />
    <path d="M2.5 13.5h9M2.5 17h9M2.5 20.5h6" />
  </>
);

export const IconAudio = ic(
  <>
    <path d="M4 10v4" />
    <path d="M8 7v10" />
    <path d="M12 9v6" />
    <path d="M16 5v14" />
    <path d="M20 10v4" />
  </>
);

export const IconSettings = ic(
  <>
    <circle cx="12" cy="12" r="3" />
    <path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M18.7 5.3l-2.1 2.1M7.4 16.6l-2.1 2.1" />
  </>
);

export const IconPair = ic(
  <>
    <circle cx="8" cy="12" r="3.5" />
    <circle cx="16" cy="12" r="3.5" />
    <path d="M11.5 12h1" />
  </>
);

export const IconBattery = ic(
  <>
    <rect x="2.5" y="8" width="16" height="8" rx="2" />
    <path d="M21.5 10.5v3" />
    <rect x="4.5" y="10" width="7" height="4" rx="1" fill="currentColor" stroke="none" />
  </>
);

export const IconBolt = ic(
  <>
    <path d="M13 2.5L5 13.5h6L11 21.5l8-11h-6z" />
  </>
);

export const IconRefresh = ic(
  <>
    <path d="M20 12a8 8 0 1 1-2.3-5.6" />
    <path d="M20 3.5V8h-4.5" />
  </>
);

export const IconMic = ic(
  <>
    <rect x="9" y="2.5" width="6" height="11" rx="3" />
    <path d="M5.5 11.5a6.5 6.5 0 0 0 13 0" />
    <path d="M12 18v3.5" />
  </>
);

export const IconSend = ic(
  <>
    <path d="M3.5 12L20 4l-4 16-4.5-6.5z" />
    <path d="M11.5 13.5L20 4" />
  </>
);
