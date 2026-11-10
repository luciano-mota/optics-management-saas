import { useId } from 'react';

/**
 * Identidade do Optics Management: uma lente em forma de olho, com a íris no gradiente
 * "prisma" (a luz que se decompõe ao atravessar a lente). Exclusiva do Optics; a página
 * pública de cada ótica usa a logo e as cores da própria ótica.
 */
export function BrandMark({ size = 36, title }: { size?: number; title?: string }) {
  const gradient = useId().replace(/:/g, '') + '-prism';
  return <svg width={size} height={size} viewBox="0 0 64 64" role={title ? 'img' : undefined}
    aria-label={title} aria-hidden={title ? undefined : true} className="brand-mark-svg">
    <defs>
      <linearGradient id={gradient} x1="22" y1="42" x2="42" y2="22" gradientUnits="userSpaceOnUse">
        <stop offset="0" stopColor="#22D3EE" />
        <stop offset=".55" stopColor="#6C5CFF" />
        <stop offset="1" stopColor="#F472B6" />
      </linearGradient>
    </defs>
    <rect width="64" height="64" rx="18" fill="#17153A" />
    <path d="M8.5 32C14.6 22.3 22.6 17.5 32 17.5S49.4 22.3 55.5 32C49.4 41.7 41.4 46.5 32 46.5S14.6 41.7 8.5 32Z"
      fill="none" stroke="#fff" strokeWidth="4.2" strokeLinejoin="round" />
    <circle cx="32" cy="32" r="10" fill={'url(#' + gradient + ')'} />
    <circle cx="35.8" cy="28.2" r="2.7" fill="#fff" />
  </svg>;
}

export function BrandLogo({ size = 34 }: { size?: number }) {
  return <span className="brand-logo">
    <BrandMark size={size} />
    <span className="brand-words"><strong>optics</strong><small>management</small></span>
  </span>;
}
