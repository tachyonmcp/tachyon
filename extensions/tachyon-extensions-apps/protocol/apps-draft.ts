interface UIResource {
    /**
     * Unique identifier for the UI resource
     *
     * MUST use the `ui://` URI scheme to distinguish UI resources from other
     * MCP resource types.
     *
     * @example
     * "ui://weather-dashboard"
     */
    uri: string;

    /**
     * Human-readable display name for the UI resource
     *
     * Used for listing and identifying the resource in host interfaces.
     *
     * @example
     * "Weather Dashboard"
     */
    name: string;

    /**
     * Optional description of the UI resource's purpose and functionality
     *
     * Provides context about what the UI does and when to use it.
     *
     * @example
     * "Interactive weather visualization with real-time updates"
     */
    description?: string;

    /**
     * MIME type of the UI content
     *
     * SHOULD be `text/html;profile=mcp-app` for HTML-based UIs in the initial MVP.
     * Other content types are reserved for future extensions.
     *
     * @example
     * "text/html;profile=mcp-app"
     */
    mimeType: string;

    /**
     * Resource metadata for security and rendering configuration
     *
     * Includes Content Security Policy configuration, dedicated domain settings,
     * and visual preferences.
     */
    _meta?: {
        ui?: UIResourceMeta;
    }
}

interface McpUiResourceCsp {
    /**
     * Origins for network requests (fetch/XHR/WebSocket)
     *
     * - Empty or omitted = no external connections (secure default)
     * - Maps to CSP `connect-src` directive
     *
     * @example
     * ["https://api.weather.com", "wss://realtime.service.com"]
     */
    connectDomains?: string[],
    /**
     * Origins for static resources (images, scripts, stylesheets, fonts, media)
     *
     * - Empty or omitted = no external resources (secure default)
     * - Wildcard subdomains supported: `https://*.example.com`
     * - Maps to CSP `img-src`, `script-src`, `style-src`, `font-src`, `media-src` directives
     *
     * @example
     * ["https://cdn.jsdelivr.net", "https://*.cloudflare.com"]
     */
    resourceDomains?: string[],
    /**
     * Origins for nested iframes
     *
     * - Empty or omitted = no nested iframes allowed (`frame-src 'none'`)
     * - Maps to CSP `frame-src` directive
     *
     * @example
     * ["https://www.youtube.com", "https://player.vimeo.com"]
     */
    frameDomains?: string[],
    /**
     * Allowed base URIs for the document
     *
     * - Empty or omitted = only same origin allowed (`base-uri 'self'`)
     * - Maps to CSP `base-uri` directive
     *
     * @example
     * ["https://cdn.example.com"]
     */
    baseUriDomains?: string[],
}

interface UIResourceMeta {
    /**
     * Content Security Policy configuration
     *
     * Servers declare which external origins their UI needs to access.
     * Hosts use this to enforce appropriate CSP headers.
     */
    csp?: McpUiResourceCsp,
    /**
     * Sandbox permissions requested by the UI
     *
     * Servers declare which browser capabilities their UI needs.
     * Hosts MAY honor these by setting appropriate iframe `allow` attributes.
     * Apps SHOULD NOT assume permissions are granted; use JS feature detection as fallback.
     */
    permissions?: {
        /**
         * Request camera access
         *
         * Maps to Permission Policy `camera` feature
         */
        camera?: {},
        /**
         * Request microphone access
         *
         * Maps to Permission Policy `microphone` feature
         */
        microphone?: {},
        /**
         * Request geolocation access
         *
         * Maps to Permission Policy `geolocation` feature
         */
        geolocation?: {},
        /**
         * Request clipboard write access
         *
         * Maps to Permission Policy `clipboard-write` feature
         */
        clipboardWrite?: {},
    },
    /**
     * Dedicated origin for view
     *
     * Optional domain for the view's sandbox origin. Useful when views need
     * stable, dedicated origins for OAuth callbacks, CORS policies, or API key allowlists.
     *
     * **Host-dependent:** The format and validation rules for this field are
     * determined by each host. Servers MUST consult host-specific documentation
     * for the expected domain format. Common patterns include:
     * - Hash-based subdomains (e.g., `{hash}.claudemcpcontent.com`)
     * - URL-derived subdomains (e.g., `www-example-com.oaiusercontent.com`)
     *
     * If omitted, Host uses default sandbox origin (typically per-conversation).
     *
     * @example
     * "a904794854a047f6.claudemcpcontent.com"
     * @example
     * "www-example-com.oaiusercontent.com"
     */
    domain?: string,
    /**
     * Visual boundary preference
     *
     * Boolean controlling whether a visible border and background is provided by the host. Specifying an
     * explicit value for this is recommended because hosts' defaults may vary.
     *
     * - `true`: Request visible border + background
     * - `false`: Request no visible border + background
     * - omitted: host decides border
     */
    prefersBorder?: boolean,
}

/**
 * Tool visibility scope - who can access the tool.
 */
export type McpUiToolVisibility = "model" | "app";

interface McpUiToolMeta {
  /** URI of UI resource for rendering tool results */
  resourceUri?: string;
  /**
   * Who can access this tool. Default: ["model", "app"]
   * - "model": Tool visible to and callable by the agent
   * - "app": Tool callable by the app from this server only
   */
  visibility?: McpUiToolVisibility[];
}
