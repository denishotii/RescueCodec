import { useChannels, useData, useT } from "../state/hooks";
import { matchesChannel } from "../lib/channels";
import { useUi } from "../state/ui";
import { isActiveMayday, sortTasks } from "../lib/format";
import { AckControl } from "./AckControl";

/** Phones only (CSS shows it below 640 px): the first open action with an Accept button that is always in reach. */
export function MobileAction() {
  const { tasks } = useData();
  const { lang, tab } = useUi();
  const t = useT();
  const { pick } = useChannels();
  if (tab === "actions") return null;
  const first = Object.values(tasks).filter((x) => x.state === "open" && !isActiveMayday(x) && matchesChannel(pick, x.channel?.name)).sort(sortTasks)[0];
  if (!first) return null;
  return (
    <div className="mobile-action" role="region" aria-label={t("open")}>
      <p>{first.text[lang]}</p>
      <AckControl task={first} />
    </div>
  );
}
