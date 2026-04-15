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

const carousel = document.querySelector("[data-gallery-carousel]");

if (carousel instanceof HTMLElement) {
  const viewport = carousel.querySelector("[data-gallery-viewport]");
  const track = carousel.querySelector("[data-gallery-track]");
  const prevButton = carousel.querySelector("[data-gallery-prev]");
  const nextButton = carousel.querySelector("[data-gallery-next]");
  const dotsRoot = carousel.querySelector("[data-gallery-dots]");
  const statusLabel = carousel.querySelector("[data-gallery-status]");

  if (
    viewport instanceof HTMLElement &&
    track instanceof HTMLElement &&
    prevButton instanceof HTMLButtonElement &&
    nextButton instanceof HTMLButtonElement &&
    dotsRoot instanceof HTMLElement &&
    statusLabel instanceof HTMLElement
  ) {
    const slides = Array.from(track.querySelectorAll(".gallery-slide"));
    const dotButtons = [];
    let currentIndex = 0;
    let pointerStartX = 0;
    let pointerStartY = 0;
    let activePointerId = null;
    const slideCount = slides.length;

    const normalizeIndex = (index) => {
      if (slideCount === 0) {
        return 0;
      }

      return (index + slideCount) % slideCount;
    };

    const updateCarousel = () => {
      track.style.transform = `translateX(-${currentIndex * 100}%)`;
      statusLabel.textContent = `${currentIndex + 1} / ${slideCount}`;
      prevButton.disabled = slideCount <= 1;
      nextButton.disabled = slideCount <= 1;

      dotButtons.forEach((button, index) => {
        const isActive = index === currentIndex;
        button.classList.toggle("is-active", isActive);
        button.setAttribute("aria-selected", String(isActive));
        button.setAttribute("aria-current", isActive ? "true" : "false");
        button.tabIndex = isActive ? 0 : -1;
      });
    };

    const moveTo = (index) => {
      currentIndex = normalizeIndex(index);
      updateCarousel();
    };

    slides.forEach((_, index) => {
      const dot = document.createElement("button");
      dot.type = "button";
      dot.className = "gallery-dot";
      dot.setAttribute("role", "tab");
      dot.setAttribute("aria-label", `Show screenshot ${index + 1}`);
      dot.addEventListener("click", () => moveTo(index));
      dotsRoot.appendChild(dot);
      dotButtons.push(dot);
    });

    prevButton.addEventListener("click", () => moveTo(currentIndex - 1));
    nextButton.addEventListener("click", () => moveTo(currentIndex + 1));

    viewport.addEventListener("keydown", (event) => {
      if (event.key === "ArrowLeft") {
        event.preventDefault();
        moveTo(currentIndex - 1);
      } else if (event.key === "ArrowRight") {
        event.preventDefault();
        moveTo(currentIndex + 1);
      } else if (event.key === "Home") {
        event.preventDefault();
        moveTo(0);
      } else if (event.key === "End") {
        event.preventDefault();
        moveTo(slideCount - 1);
      }
    });

    const resetPointer = () => {
      activePointerId = null;
      pointerStartX = 0;
      pointerStartY = 0;
    };

    viewport.addEventListener("pointerdown", (event) => {
      if (event.pointerType === "mouse" && event.button !== 0) {
        return;
      }

      activePointerId = event.pointerId;
      pointerStartX = event.clientX;
      pointerStartY = event.clientY;
      viewport.setPointerCapture(event.pointerId);
    });

    viewport.addEventListener("pointerup", (event) => {
      if (activePointerId !== event.pointerId) {
        return;
      }

      const deltaX = event.clientX - pointerStartX;
      const deltaY = event.clientY - pointerStartY;
      const requiredSwipe = Math.max(36, viewport.clientWidth * 0.08);

      if (Math.abs(deltaX) > Math.abs(deltaY) && Math.abs(deltaX) > requiredSwipe) {
        if (deltaX < 0) {
          moveTo(currentIndex + 1);
        } else {
          moveTo(currentIndex - 1);
        }
      }

      if (viewport.hasPointerCapture(event.pointerId)) {
        viewport.releasePointerCapture(event.pointerId);
      }
      resetPointer();
    });

    viewport.addEventListener("pointercancel", resetPointer);
    viewport.addEventListener("lostpointercapture", resetPointer);

    if (slides.length <= 1) {
      prevButton.hidden = true;
      nextButton.hidden = true;
      dotsRoot.hidden = true;
      statusLabel.hidden = true;
    }

    updateCarousel();
  }
}
