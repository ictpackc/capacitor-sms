import "@capacitor/core";
declare module "@capacitor/core" {
  interface PluginRegistry {
    IctpackSms: IctpackSmsPlugin;
  }
}

export interface IctpackSmsPlugin {
  startWatch(): Promise<any>;
  stopWatch(): Promise<any>;
  /**
     * Send the sms
     * @param {SmsSendOptions} options
     * @returns {Promise<void>}
     */
    send(options: SmsSendOptions): Promise<void>;
}

export interface SmsSendOptions {
  numbers: string[];
  text: string;
}