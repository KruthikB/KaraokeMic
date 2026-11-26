function pagesBasePath(value) {
  if (!value || value === "/") {
    return undefined;
  }
  const withSlash = value.startsWith("/") ? value : `/${value}`;
  return withSlash.replace(/\/+$/, "");
}

module.exports = ({ config }) => {
  const baseUrl = pagesBasePath(process.env.PAGES_BASE_PATH);
  return {
    ...config,
    experiments: {
      ...(config.experiments ?? {}),
      ...(baseUrl ? { baseUrl } : {}),
    },
  };
};

module.exports.pagesBasePath = pagesBasePath;
