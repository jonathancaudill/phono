(function () {
  "use strict";

  var REDIRECT_URI = "http://127.0.0.1:43821/callback";
  var PAYLOAD_TYPE = "phono.spotify.webapi";
  var PAYLOAD_VERSION = 1;

  document.querySelectorAll(".copy-btn").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var id = btn.getAttribute("data-copy");
      var el = document.getElementById(id);
      if (!el) return;
      navigator.clipboard.writeText(el.textContent.trim()).then(function () {
        var prev = btn.textContent;
        btn.textContent = "Copied";
        setTimeout(function () { btn.textContent = prev; }, 1200);
      });
    });
  });

  var form = document.getElementById("setup-form");
  var formError = document.getElementById("form-error");
  var qrSection = document.getElementById("qr-section");
  var qrOutput = document.getElementById("qr-output");

  form.addEventListener("submit", function (e) {
    e.preventDefault();
    formError.hidden = true;

    var clientId = document.getElementById("client-id").value.trim();
    var clientSecret = document.getElementById("client-secret").value.trim();

    if (!clientId || !clientSecret) {
      formError.textContent = "Client ID and Client Secret are required.";
      formError.hidden = false;
      return;
    }

    var payload = JSON.stringify({
      v: PAYLOAD_VERSION,
      type: PAYLOAD_TYPE,
      client_id: clientId,
      client_secret: clientSecret,
      redirect_uri: REDIRECT_URI,
    });

    try {
      qrOutput.innerHTML = "";
      renderQr(qrOutput, payload, 8);
      qrSection.hidden = false;
      qrSection.scrollIntoView({ behavior: "smooth", block: "nearest" });
    } catch (err) {
      formError.textContent = err instanceof Error ? err.message : "Failed to generate QR code.";
      formError.hidden = false;
    }
  });

  function renderQr(container, text, scale) {
    var qr = qrcodegen.QrCode.encodeText(text, qrcodegen.QrCode.Ecc.MEDIUM);
    var size = qr.size;
    var border = 4;
    var canvas = document.createElement("canvas");
    canvas.width = (size + border * 2) * scale;
    canvas.height = (size + border * 2) * scale;
    var ctx = canvas.getContext("2d");
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = "#000000";
    for (var y = 0; y < size; y++) {
      for (var x = 0; x < size; x++) {
        if (qr.getModule(x, y)) {
          ctx.fillRect((x + border) * scale, (y + border) * scale, scale, scale);
        }
      }
    }
    container.appendChild(canvas);
  }
})();
