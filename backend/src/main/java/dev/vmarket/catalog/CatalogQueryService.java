package dev.vmarket.catalog;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogQueryService {
    private final JdbcClient jdbc;
    private final CatalogCache cache;

    public CatalogQueryService(JdbcClient jdbc, CatalogCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    @Transactional(readOnly = true)
    public List<ProductView> findActiveProducts() {
        var metadata = cache.read();
        if (metadata == null) {
            metadata = jdbc.sql("""
                            SELECT p.sku, v.name AS vendor_name, p.name, p.category, p.description,
                                   p.price_minor, p.currency, p.image_path, p.image_alt
                            FROM product p JOIN vendor v ON v.id = p.vendor_id
                            WHERE p.active = TRUE AND v.active = TRUE
                            ORDER BY p.sku
                            """).query(ProductMetadata.class).list();
            cache.write(metadata);
        }
        // Availability and eligibility always come from PostgreSQL, including on a Redis hit.
        // This is a display snapshot, not a stock reservation; checkout still locks and revalidates.
        var stock = jdbc.sql("""
                        SELECT p.sku, s.on_hand - s.reserved AS available_quantity
                        FROM product p
                        JOIN vendor v ON v.id = p.vendor_id
                        JOIN inventory_stock s ON s.product_id = p.id
                        WHERE p.active = TRUE AND v.active = TRUE
                        """).query(StockView.class).list().stream()
                .collect(java.util.stream.Collectors.toMap(StockView::sku, StockView::availableQuantity));
        return metadata.stream()
                .filter(product -> stock.containsKey(product.sku()))
                .map(product -> new ProductView(
                        product.sku(),
                        product.vendorName(),
                        product.name(),
                        product.category(),
                        product.description(),
                        product.priceMinor(),
                        product.currency(),
                        product.imagePath(),
                        product.imageAlt(),
                        stock.get(product.sku())))
                .toList();
    }

    private record StockView(String sku, int availableQuantity) {}

    public record ProductMetadata(
            String sku,
            String vendorName,
            String name,
            String category,
            String description,
            int priceMinor,
            String currency,
            String imagePath,
            String imageAlt) {}

    public record ProductView(
            String sku,
            String vendorName,
            String name,
            String category,
            String description,
            int priceMinor,
            String currency,
            String imagePath,
            String imageAlt,
            int availableQuantity) {}
}
