terraform {
  required_version = ">= 1.9"

  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 6.0"
    }
  }
}

locals {
  # One place to change the naming scheme, and one place to add a tag that must appear on
  # every resource for cost attribution.
  name = var.name_prefix

  common_tags = merge(
    {
      Project   = "fintrack"
      ManagedBy = "terraform"
    },
    var.tags,
  )
}

# --- Network -------------------------------------------------------------------------

resource "oci_core_vcn" "this" {
  compartment_id = var.compartment_id
  display_name   = "${local.name}-vcn"
  cidr_blocks    = [var.vcn_cidr]
  dns_label      = replace(local.name, "-", "")
  freeform_tags  = local.common_tags
}

resource "oci_core_internet_gateway" "this" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${local.name}-igw"
  enabled        = true
  freeform_tags  = local.common_tags
}

resource "oci_core_route_table" "public" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${local.name}-public-rt"

  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.this.id
  }

  freeform_tags = local.common_tags
}

# Stateful rules, so a reply to an allowed inbound connection is permitted automatically and
# the egress rule does not have to describe return traffic.
resource "oci_core_security_list" "public" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${local.name}-public-sl"

  # Unrestricted egress: the node has to reach package mirrors, container registries and
  # Let's Encrypt. Restricting it would mean maintaining an allowlist of every CDN those use.
  egress_security_rules {
    destination      = "0.0.0.0/0"
    destination_type = "CIDR_BLOCK"
    protocol         = "all"
    stateless        = false
  }

  # SSH, restricted to one address by default. See the variable's own note on why.
  ingress_security_rules {
    source      = var.ssh_allowed_cidr
    source_type = "CIDR_BLOCK"
    protocol    = "6" # TCP
    stateless   = false
    description = "SSH, restricted"

    tcp_options {
      min = 22
      max = 22
    }
  }

  ingress_security_rules {
    source      = "0.0.0.0/0"
    source_type = "CIDR_BLOCK"
    protocol    = "6"
    stateless   = false
    description = "HTTP - redirected to HTTPS by the ingress, and needed for ACME http-01"

    tcp_options {
      min = 80
      max = 80
    }
  }

  ingress_security_rules {
    source      = "0.0.0.0/0"
    source_type = "CIDR_BLOCK"
    protocol    = "6"
    stateless   = false
    description = "HTTPS"

    tcp_options {
      min = 443
      max = 443
    }
  }

  # ICMP path-MTU-discovery. Without it, connections to the node hang rather than fail when
  # a smaller MTU is in the path - one of the more baffling failure modes to diagnose.
  ingress_security_rules {
    source      = "0.0.0.0/0"
    source_type = "CIDR_BLOCK"
    protocol    = "1"
    stateless   = false
    description = "ICMP path MTU discovery"

    icmp_options {
      type = 3
      code = 4
    }
  }

  freeform_tags = local.common_tags
}

resource "oci_core_subnet" "public" {
  compartment_id             = var.compartment_id
  vcn_id                     = oci_core_vcn.this.id
  display_name               = "${local.name}-public-subnet"
  cidr_block                 = var.subnet_cidr
  route_table_id             = oci_core_route_table.public.id
  security_list_ids          = [oci_core_security_list.public.id]
  dns_label                  = "public"
  prohibit_public_ip_on_vnic = false
  freeform_tags              = local.common_tags
}

# --- Compute -------------------------------------------------------------------------

resource "oci_core_instance" "k3s" {
  compartment_id      = var.compartment_id
  availability_domain = var.availability_domain
  display_name        = "${local.name}-k3s"
  shape               = var.instance_shape

  shape_config {
    ocpus         = var.instance_ocpus
    memory_in_gbs = var.instance_memory_gb
  }

  source_details {
    source_type             = "image"
    source_id               = var.image_id
    boot_volume_size_in_gbs = var.boot_volume_gb
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.public.id
    assign_public_ip = true
    hostname_label   = "k3s"
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data           = base64encode(file("${path.module}/cloud-init.yaml"))
  }

  freeform_tags = local.common_tags

  lifecycle {
    # The image is updated by Oracle periodically. Replacing the instance because of that
    # would destroy the node - and its volumes - on an unrelated apply.
    ignore_changes = [source_details[0].source_id]
  }
}
