package cloud.xuantong.integration.spring.cloud.config;

import org.springframework.boot.context.config.ConfigDataResource;

import java.util.Objects;

public final class XuantongConfigDataResource extends ConfigDataResource {
    private final String dataId;
    private final boolean optional;
    private final boolean profileSpecific;
    private final XuantongConfigDataSettings settings;

    XuantongConfigDataResource(
            String dataId,
            boolean optional,
            boolean profileSpecific,
            XuantongConfigDataSettings settings) {
        this.dataId = dataId;
        this.optional = optional;
        this.profileSpecific = profileSpecific;
        this.settings = settings;
    }

    public String getDataId() {
        return dataId;
    }

    public boolean isOptional() {
        return optional;
    }

    public boolean isProfileSpecific() {
        return profileSpecific;
    }

    XuantongConfigDataSettings getSettings() {
        return settings;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof XuantongConfigDataResource resource)) {
            return false;
        }
        return optional == resource.optional
                && profileSpecific == resource.profileSpecific
                && dataId.equals(resource.dataId)
                && settings.equals(resource.settings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dataId, optional, profileSpecific, settings);
    }

    @Override
    public String toString() {
        return "XuantongConfigDataResource[dataId=" + dataId
                + ", optional=" + optional
                + ", profileSpecific=" + profileSpecific + "]";
    }
}
