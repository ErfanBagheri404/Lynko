export interface Capabilities {
  screen_capture: boolean;
  input_injection: boolean;
  text_input: boolean;
  clipboard_sync: boolean;
  file_transfer: boolean;
  notifications: boolean;
  audio_capture: boolean;
  battery_status: boolean;
}

export interface DeviceSummary {
  id: string;
  name: string;
  address: string;
  caps: Capabilities;
  paired: boolean;
  online: boolean;
}

export interface DiscoveryEvent {
  found: DeviceSummary[];
}

export const emptyCaps: Capabilities = {
  screen_capture: false,
  input_injection: false,
  text_input: false,
  clipboard_sync: false,
  file_transfer: false,
  notifications: false,
  audio_capture: false,
  battery_status: false,
};
