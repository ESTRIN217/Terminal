package com.estrin217.filemanager;

import java.io.File;
import java.util.Comparator;

public enum FileSortOption {

    NAME("Name") {
        @Override
        public Comparator<File> getComparator(boolean ascending) {
            return (a, b) -> {
                int dirCompare = Boolean.compare(b.isDirectory(), a.isDirectory());
                if (dirCompare != 0) return ascending ? dirCompare : -dirCompare;
                return ascending ?
                    a.getName().compareToIgnoreCase(b.getName()) :
                    b.getName().compareToIgnoreCase(a.getName());
            };
        }
    },

    DATE("Date") {
        @Override
        public Comparator<File> getComparator(boolean ascending) {
            return (a, b) -> {
                int dirCompare = Boolean.compare(b.isDirectory(), a.isDirectory());
                if (dirCompare != 0) return ascending ? dirCompare : -dirCompare;
                return ascending ?
                    Long.compare(a.lastModified(), b.lastModified()) :
                    Long.compare(b.lastModified(), a.lastModified());
            };
        }
    },

    SIZE("Size") {
        @Override
        public Comparator<File> getComparator(boolean ascending) {
            return (a, b) -> {
                int dirCompare = Boolean.compare(b.isDirectory(), a.isDirectory());
                if (dirCompare != 0) return ascending ? dirCompare : -dirCompare;
                long sizeA = a.isDirectory() ? 0 : a.length();
                long sizeB = b.isDirectory() ? 0 : b.length();
                return ascending ?
                    Long.compare(sizeA, sizeB) :
                    Long.compare(sizeB, sizeA);
            };
        }
    },

    TYPE("Type") {
        @Override
        public Comparator<File> getComparator(boolean ascending) {
            return (a, b) -> {
                int dirCompare = Boolean.compare(b.isDirectory(), a.isDirectory());
                if (dirCompare != 0) return ascending ? dirCompare : -dirCompare;
                String extA = getExtension(a.getName());
                String extB = getExtension(b.getName());
                int extCompare = extA.compareToIgnoreCase(extB);
                if (extCompare != 0) return ascending ? extCompare : -extCompare;
                return ascending ?
                    a.getName().compareToIgnoreCase(b.getName()) :
                    b.getName().compareToIgnoreCase(a.getName());
            };
        }
    };

    private final String displayName;

    FileSortOption(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public abstract Comparator<File> getComparator(boolean ascending);

    private static String getExtension(String name) {
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx) : "";
    }
}
