export interface AutomationPromptPreset {
  id: string;
  title: string;
  description: string;
  prompt: string;
  scheduleEnabled: boolean;
  cron: string;
}
