import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import "./globals.css";
import { Providers } from "@/app/providers";
import { siteOrigin } from "@/lib/site";

export const metadata: Metadata = {
  metadataBase: siteOrigin,
  title: {
    default: "V-Market | Next-Gen Multi-Vendor Commerce",
    template: "%s | V-Market",
  },
  description:
    "V-Market is a performance-first multi-vendor e-commerce platform optimized for mobile catalog delivery, Core Web Vitals, and race-safe cart interactions.",
  applicationName: "V-Market",
  keywords: [
    "multi-vendor marketplace",
    "e-commerce platform",
    "Next.js commerce",
    "Core Web Vitals",
    "optimized product catalog",
  ],
  authors: [{ name: "V-Market" }],
  creator: "V-Market",
  publisher: "V-Market",
  alternates: {
    canonical: "/",
  },
  openGraph: {
    type: "website",
    locale: "en_US",
    url: "/",
    siteName: "V-Market",
    title: "V-Market | Next-Gen Multi-Vendor Commerce",
    description:
      "A high-traffic marketplace architecture with optimized media delivery and optimistic, cancellation-aware cart operations.",
    images: [
      {
        url: "/social/v-market-og.jpg",
        width: 1200,
        height: 630,
        alt: "V-Market performance-first product catalog",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "V-Market | Next-Gen Multi-Vendor Commerce",
    description:
      "Optimized catalog media, Core Web Vitals defaults, and race-safe cart operations for mobile shoppers.",
    images: ["/social/v-market-og.jpg"],
  },
  robots: {
    index: true,
    follow: true,
    googleBot: {
      index: true,
      follow: true,
      "max-image-preview": "large",
      "max-snippet": -1,
      "max-video-preview": -1,
    },
  },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: "#f7f5ef",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: ReactNode;
}>) {
  return (
    <html lang="en">
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
