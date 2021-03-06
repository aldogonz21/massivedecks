import {
  CastStatus,
  InboundPort,
  OutboundPort,
  RemoteControlCommand
} from "../../elm/MassiveDecks";
import { channel } from "./shared";

export const register = (
  tryCast: InboundPort<RemoteControlCommand>,
  castStatus: OutboundPort<CastStatus>
) => {
  new Client(tryCast, castStatus);
};

declare const cast: any;

let whenAvailable: (() => void)[] | null = [];

window["__onGCastApiAvailable"] = function(isAvailable: Boolean) {
  if (isAvailable) {
    cast.framework.CastContext.getInstance().setOptions({
      receiverApplicationId: "A6799922",
      autoJoinPolicy: chrome.cast.AutoJoinPolicy.ORIGIN_SCOPED
    });
    if (whenAvailable != null) {
      for (const callback of whenAvailable) {
        callback();
      }
      whenAvailable = null;
    } else {
      console.warn("Cast API initialized twice.");
    }
  }
};

/**
 * The client that lives on the MassiveDecks client.
 */
class Client {
  readonly tryCast: InboundPort<RemoteControlCommand>;
  readonly status: OutboundPort<CastStatus>;
  readonly commandQueue: RemoteControlCommand[];

  constructor(
    tryCast: InboundPort<RemoteControlCommand>,
    status: OutboundPort<CastStatus>
  ) {
    if (whenAvailable == null) {
      this.onceCastApiAvailable();
    } else {
      whenAvailable.push(() => this.onceCastApiAvailable());
    }
    this.tryCast = tryCast;
    this.status = status;
    this.commandQueue = [];
  }

  onceCastApiAvailable(): void {
    this.tryCast.subscribe((command: RemoteControlCommand) =>
      this.toggleCast(command)
    );
    const context = cast.framework.CastContext.getInstance();
    context.addEventListener(
      cast.framework.CastContextEventType.CAST_STATE_CHANGED,
      (event: cast.framework.CastStateEventData) =>
        this.onCastStateChanged(event)
    );
    context.addEventListener(
      cast.framework.CastContextEventType.SESSION_STATE_CHANGED,
      (event: cast.framework.SessionStateEventData) =>
        this.onSessionStateChanged(event)
    );
    this.status.send(context.getCastState());
  }

  toggleCast(command: RemoteControlCommand): void {
    const context = cast.framework.CastContext.getInstance();
    if (context.getCastState() === cast.framework.CastState.CONNECTED) {
      context.endCurrentSession(true);
    } else {
      this.commandQueue.push(command);
      context
        .requestSession()
        .then(function(e: chrome.cast.ErrorCode) {
          if (e) {
            console.error(e);
          }
        })
        .catch(function(e: Error) {
          console.error(e);
        });
    }
  }

  onCastStateChanged(event: cast.framework.CastStateEventData): void {
    let status: CastStatus;
    if (event.castState === cast.framework.CastState.NO_DEVICES_AVAILABLE) {
      status = { status: "NoDevicesAvailable" };
    } else if (event.castState === cast.framework.CastState.NOT_CONNECTED) {
      status = { status: "NotConnected" };
    } else if (event.castState === cast.framework.CastState.CONNECTING) {
      status = { status: "Connecting" };
    } else if (event.castState === cast.framework.CastState.CONNECTED) {
      status = { status: "Connected" };
      const session = cast.framework.CastContext.getInstance().getCurrentSession();
      if (session) {
        status.name = session.getCastDevice().friendlyName;
      }
    } else {
      throw new Error("Unknown cast state.");
    }
    this.status.send(status);
  }

  onSessionStateChanged(event: cast.framework.SessionStateEventData): void {
    if (
      (!event.errorCode &&
        event.sessionState === cast.framework.SessionState.SESSION_STARTED) ||
      event.sessionState === cast.framework.SessionState.SESSION_RESUMED
    ) {
      for (const command of this.commandQueue) {
        event.session
          .sendMessage(channel, JSON.stringify(command))
          .catch((e: Error) => console.error(e));
      }
    }
  }
}
