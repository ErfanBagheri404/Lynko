// Pointer → normalized phone coords for the mirrored canvas.
// Mirrors App.tsx's norm(): the canvas shows the phone bitmap letterboxed
// (object-fit:contain) or cropped (object-fit:cover, "Fill"), and zoom scales
// the bitmap around the canvas center. Side taps were landing in the black
// bars because the math measured the element box; map against the painted
// bitmap rect instead. Pure function so it is testable without a browser.
export function mapPointer(el, naturalWidth, naturalHeight, fill, zoom, clientX, clientY) {
  const iw = naturalWidth || el.width, ih = naturalHeight || el.height;
  const contain = Math.min(el.width / iw, el.height / ih);
  const scale = (fill ? Math.max(el.width / iw, el.height / ih) : contain) * zoom;
  const w = iw * scale, h = ih * scale;
  const left = el.left + (el.width - w) / 2;
  const top = el.top + (el.height - h) / 2;
  return {
    x: Math.min(1, Math.max(0, (clientX - left) / w)),
    y: Math.min(1, Math.max(0, (clientY - top) / h)),
    inside: clientX >= left && clientX <= left + w && clientY >= top && clientY <= top + h,
  };
}
