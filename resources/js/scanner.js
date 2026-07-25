const baseUrl = "/_native/api/call";

const VALID_FORMATS = [
  "qr",
  "ean13",
  "ean8",
  "code128",
  "code39",
  "upca",
  "upce",
  "all",
];

async function bridgeCall(method, params = {}) {
  const response = await fetch(baseUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-CSRF-TOKEN":
        document.querySelector('meta[name="csrf-token"]')?.content || "",
    },
    body: JSON.stringify({ method, params }),
  });

  const result = await response.json();

  if (result.status === "error") {
    const error = new Error(
      result.message || "The scanner could not be started.",
    );
    error.code = result.code;
    throw error;
  }

  return result.data;
}

class PendingScan {
  constructor() {
    this._id = null;
    this._prompt = null;
    this._continuous = false;
    this._formats = ["qr"];
    this._started = false;
  }

  prompt(prompt) {
    this._prompt = prompt;
    return this;
  }

  continuous(continuous = true) {
    this._continuous = continuous;
    return this;
  }

  formats(formats) {
    if (!Array.isArray(formats) || formats.length === 0) {
      throw new Error("At least one barcode format must be specified.");
    }

    const invalid = formats.filter((format) => !VALID_FORMATS.includes(format));
    if (invalid.length > 0) {
      throw new Error(
        `Invalid barcode format(s): ${invalid.join(", ")}. Valid formats are: ${VALID_FORMATS.join(", ")}.`,
      );
    }

    this._formats = [...new Set(formats)];
    return this;
  }

  id(id) {
    this._id = id;
    return this;
  }

  getId() {
    return this._id;
  }

  then(resolve, reject) {
    if (this._started) {
      return resolve();
    }
    this._started = true;

    return bridgeCall("MobileScanner.Scan", {
      prompt: this._prompt ?? "Scan Code",
      continuous: this._continuous,
      formats: this._formats,
      id: this._id,
    }).then(resolve, reject);
  }
}

export const Scanner = {
  scan: () => new PendingScan(),

  stop: (id) => bridgeCall("MobileScanner.Stop", { id: id ?? null }),
};

export { PendingScan };

const _eventListeners = {};
let _listenerInstalled = false;

function installListener() {
  if (_listenerInstalled) {
    return;
  }

  document.addEventListener("native-event", (e) => {
    const eventName = e.detail.event.replace(/^(\\)+/, "");
    const payload = e.detail.payload;
    (_eventListeners[eventName] || []).forEach((callback) =>
      callback(payload, eventName),
    );
  });

  _listenerInstalled = true;
}

export function On(eventName, callback) {
  installListener();
  (_eventListeners[eventName] ??= []).push(callback);
}

export function Off(eventName, callback) {
  if (_eventListeners[eventName]) {
    _eventListeners[eventName] = _eventListeners[eventName].filter(
      (cb) => cb !== callback,
    );
  }
}

export const Events = {
  Scanner: {
    CodeScanned: "Sandip\\Scanner\\Native\\Events\\Scanner\\CodeScanned",
    Cancelled: "Sandip\\Scanner\\Native\\Events\\Scanner\\Cancelled",
  },
};

export default { Scanner, On, Off, Events, PendingScan };
