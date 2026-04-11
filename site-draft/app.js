const form = document.getElementById("beta-request-form");
const status = document.getElementById("form-status");

if (form instanceof HTMLFormElement && status) {
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    status.className = "form-status";

    const action = form.action;
    if (!action || action.includes("REPLACE_WITH_YOUR_FORM_ID")) {
      status.textContent =
        "Replace the placeholder Formspree endpoint in site/index.html before publishing the request form.";
      status.classList.add("is-error");
      return;
    }

    const data = new FormData(form);

    try {
      status.textContent = "Sending request...";
      const response = await fetch(action, {
        method: "POST",
        body: data,
        headers: {
          Accept: "application/json",
        },
      });

      if (!response.ok) {
        throw new Error("Submission failed");
      }

      form.reset();
      status.textContent = "Request sent. You are on the beta waitlist now.";
      status.classList.add("is-success");
    } catch (error) {
      status.textContent =
        "That request did not send. Try again in a moment.";
      status.classList.add("is-error");
    }
  });
}
