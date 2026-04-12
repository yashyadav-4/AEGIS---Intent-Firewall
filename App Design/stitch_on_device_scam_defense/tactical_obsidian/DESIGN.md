```markdown
# Design System Specification: The Tactical Obsidian

## 1. Overview & Creative North Star

### Creative North Star: "The Tactical Obsidian"
This design system is engineered for high-stakes environments where clarity equals security. Moving beyond the "Hollywood Hacker" trope, it embraces a **Cyber-Tactical** aesthetic: a marriage of brutalist precision and high-tech utility. It is designed to feel like a heads-up display (HUD) for a digital vanguard—authoritative, cold, and hyper-efficient.

### Design Philosophy: Digital Brutalism
To break the "template" look common in SaaS, this system rejects the softness of modern web design. We eliminate the "crutches" of border-radii and soft shadows, replacing them with **intentional asymmetry** and **tonal depth**. Layouts should feel like a technical schematic—architectural, grounded, and unapologetically sharp. We prioritize the "wireframe" look for active states to emphasize the underlying structure of the data.

---

## 2. Colors

The palette is rooted in the void. We use high-contrast accents to guide the eye through dense data streams without cluttering the cognitive load.

### The Palette (Material Design Mapping)
*   **Background / Surface:** `#0B0C10` (The Obsidian Core)
*   **Primary / Active:** `#45F3FF` (Electric Cyan) — Used for wireframes, active states, and successful intents.
*   **Error / Blocked:** `#FF2A2A` (Laser Red) — Reserved strictly for threats, blocked intents, and critical alerts.
*   **Secondary / Neutral:** `#C5C6C7` — Used for secondary data and inactive labels.

### The "No-Line" Rule
Standard 1px borders are strictly prohibited for sectioning. Structural boundaries must be achieved through:
1.  **Background Shifts:** Distinguish the sidebar from the main stage by shifting from `surface-dim` (#121317) to `surface-container-low` (#1a1b20).
2.  **Negative Space:** Use the grid's rhythm to define sections.
3.  **The Ghost Border:** If containment is required for complex data, use the `outline_variant` (#3b494a) at 15% opacity.

### Surface Hierarchy & Nesting
Treat the UI as a series of stacked obsidian plates.
*   **Base:** `surface_container_lowest` (#0d0e12) for the master background.
*   **Nesting:** Place `surface_container_high` (#292a2e) cards on top of `surface_container_low` sections to create "elevation" through color value rather than shadows.

---

## 3. Typography

The typography is the backbone of the tactical aesthetic. We utilize **Space Grotesk**—a font that carries the soul of a monospaced typeface with the legibility of a high-end sans-serif.

| Level | Size | Case | Tracking | Intent |
| :--- | :--- | :--- | :--- | :--- |
| **Display-LG** | 3.5rem | Uppercase | -0.02em | High-level system status codes. |
| **Headline-MD** | 1.75rem | Uppercase | 0.05em | Section headers, terminal titles. |
| **Title-SM** | 1rem | Sentence | 0.02em | Widget titles, module labels. |
| **Body-MD** | 0.875rem | Sentence | 0.01em | Standard data readouts. |
| **Label-MD** | 0.75rem | Uppercase | 0.1em | Metadata, timestamps, micro-copy. |

**Editorial Note:** Use intentional asymmetry by pairing a `Display-LG` metric (e.g., "99%") with a tiny, vertical `Label-MD` (e.g., "SYSTEM HEALTH") rotated -90 degrees to create a signature "technical document" feel.

---

## 4. Elevation & Depth

### The Layering Principle
In this system, "Up" is "Brighter." We do not use traditional shadows.
*   **Layer 0:** `surface_dim` (#121317)
*   **Layer 1:** `surface_container_low` (#1a1b20)
*   **Layer 2:** `surface_container_highest` (#343439)

### Zero-Radius Architecture
Every element—buttons, cards, inputs, and menus—must have a **0px border radius**. This creates a sharp, monolithic look that feels custom-built for performance.

### Tactical Glassmorphism
For floating overlays (modals or tooltips), use a semi-transparent `surface_container_high` with a heavy `backdrop-filter: blur(12px)`. This suggests a "translucent HUD" overlaying the core data stream, maintaining environmental awareness.

---

## 5. Components

### Buttons (Tactical Trigger)
*   **Primary:** Solid `primary_container` (#45f3ff) with `on_primary` (#00363a) text. No rounded corners. On hover, trigger a 1px `primary` wireframe stroke around the button.
*   **Secondary:** Ghost style. 1px stroke of `outline` (#849395). On hover, fill with 10% opacity of `primary`.
*   **Destructive:** Solid `error` (#FF2A2A). Use sparingly for "Purge" or "Block" actions.

### Data Inputs
*   **Fields:** No background. A single bottom border (1px) using `outline`. Upon focus, the border shifts to `primary` (#45f3ff) and a subtle 2% primary-colored glow fills the field.
*   **Labels:** Always `Label-MD` (Uppercase) positioned exactly 8px above the input line.

### Cards & Lists
*   **Card Design:** Forbid dividers. Use a `surface_container_low` background and separate items with 16px of vertical white space.
*   **The "Active" Intent:** Active list items are denoted by a 4px vertical bar of `primary` on the far left edge and a subtle `surface_container_high` background shift.

### Tactical Additions: The "Scan-Line" & "Grid-Mesh"
*   **The Mesh:** On the lowest surface layer, apply a subtle CSS repeating linear gradient to create a 20px x 20px grid of 1px lines at 3% opacity. This reinforces the "tactical map" feeling.
*   **The Pulse:** For critical alerts, use a non-blurred, 1px `error` border that pulses in opacity (0.2 to 0.8).

---

## 6. Do's and Don'ts

### Do
*   **Do** use extreme contrast. A small `primary` element on a `surface_dim` background should feel like a light in a dark room.
*   **Do** align elements to a strict 8px grid. Misalignment breaks the "tactical" illusion.
*   **Do** use monospaced numbers for all data tables to ensure columns align perfectly.

### Don't
*   **Don't** use border-radius. Ever. 1px of rounding is 1px too many.
*   **Don't** use drop shadows. They suggest soft, natural light; this system is lit by cold, digital LEDs.
*   **Don't** use standard "Blue" for links. If it’s an action, it’s Cyan. If it’s a warning, it’s Red. Everything else is tonal grey.
*   **Don't** use "Illustrations." Use technical wireframes, SVG icons with 1.5px stroke weights, or raw data visualizations.