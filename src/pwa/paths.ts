/**
 * Directory that holds index.html, whether the site is at `/` or `/KaraokeMic/`.
 * A path without a trailing slash is still a folder when the last piece has no file extension.
 */
export function appDirectoryHref(pageHref: string): string {
  const url = new URL(pageHref);
  const last = url.pathname.split("/").filter((part) => part.length > 0).pop() ?? "";
  const looksLikeFile = last.includes(".");
  if (looksLikeFile) {
    url.pathname = url.pathname.slice(0, url.pathname.lastIndexOf("/") + 1);
  } else if (!url.pathname.endsWith("/")) {
    url.pathname = `${url.pathname}/`;
  }
  return new URL(".", url).href;
}

export function appFileUrl(file: string, pageHref: string): string {
  return new URL(file, appDirectoryHref(pageHref)).href;
}

export function pagesBasePath(value: string | undefined): string | undefined {
  if (!value || value === "/") {
    return undefined;
  }
  const withSlash = value.startsWith("/") ? value : `/${value}`;
  return withSlash.replace(/\/+$/, "");
}
