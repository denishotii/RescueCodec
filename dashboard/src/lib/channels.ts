import type { Health, MessageRecord, Task } from "../api/types";

/** "all" or a channel name. */
export type ChannelPick = string;

/** Names of the channels that records and tasks actually use, in order of first appearance. */
export function channelsInUse(messages: Pick<MessageRecord, "channel">[], tasks: Pick<Task, "channel">[]): string[] {
  const seen: string[] = [];
  for (const x of [...messages, ...tasks]) {
    const name = x.channel?.name;
    if (name && !seen.includes(name)) seen.push(name);
  }
  return seen;
}

/** Filter choices: the channels the gateway listens on (health), then any other channel seen in the data. */
export function channelOptions(health: Health | null, messages: Pick<MessageRecord, "channel">[], tasks: Pick<Task, "channel">[]): string[] {
  const out = (health?.radio.channels ?? []).map((c) => c.name).filter(Boolean);
  for (const name of channelsInUse(messages, tasks)) if (!out.includes(name)) out.push(name);
  return out;
}

/** "all" matches everything. A channel name matches without case, like the gateway does. */
export function matchesChannel(pick: ChannelPick, name: string | null | undefined): boolean {
  if (pick === "all") return true;
  return !!name && name.toLowerCase() === pick.toLowerCase();
}

/** Chips and the filter only help when more than one channel is around. */
export const showChannels = (names: string[]) => names.length > 1;
